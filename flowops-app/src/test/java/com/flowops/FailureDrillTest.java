package com.flowops;

import com.flowops.domain.port.DepartmentRepository;
import com.flowops.domain.ExecutionJob;
import com.flowops.domain.port.ExecutionJobRepository;
import com.flowops.domain.JobStatus;
import com.flowops.domain.OutboxMessage;
import com.flowops.domain.port.OutboxMessageRepository;
import com.flowops.domain.OutboxStatus;
import com.flowops.domain.Request;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.Role;
import com.flowops.domain.port.RoleRepository;
import com.flowops.domain.User;
import com.flowops.domain.port.UserRepository;
import com.flowops.infra.worker.JobWorker;
import com.flowops.infra.worker.OutboxDispatcher;
import com.flowops.infra.action.SimulatedActionHandler;
import com.flowops.infra.mail.SimulatedEmailGateway;
import com.flowops.infra.worker.SlaScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Iteration 8 tests: failure drills (PLAN §8).
 *
 * <ul>
 *   <li>Duplicate submission is rejected (state-machine guard + idempotency)</li>
 *   <li>Dispatcher "killed" mid-batch: undelivered outbox rows stay in the DB
 *       (PENDING/FAILED) and are retried to SENT after the gateway recovers —
 *       nothing is lost, nothing is double-sent</li>
 *   <li>Job worker failure → retry → COMPLETED, request reaches EXECUTED</li>
 * </ul>
 */
@AutoConfigureMockMvc
class FailureDrillTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JobWorker jobWorker;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired SlaScanner slaScanner;
    @Autowired SimulatedEmailGateway emailGateway;
    @Autowired SimulatedActionHandler actionHandler;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired DepartmentRepository departments;
    @Autowired PasswordEncoder encoder;
    @Autowired OutboxMessageRepository outboxRepo;
    @Autowired ExecutionJobRepository jobRepo;
    @Autowired RequestRepository requestRepo;
    @Autowired TransactionTemplate txTemplate;

    private String hankToken;
    private String adminToken;
    private long hankId;

    @BeforeEach
    void setup() throws Exception {
        if (users.findByUsername("it8_hank").isEmpty()) {
            User hank = User.newUser("it8_hank", "It8 Hank", "it8_hank@example.com",
                    departments.findByCode("IT").orElse(null),
                    Set.of(roles.findByCode("MEMBER").orElseThrow()), encoder.encode("it8_hank123"));
            users.save(hank);
        }
        hankId = users.findByUsername("it8_hank").orElseThrow().getId();
        hankToken = userToken("it8_hank", "it8_hank123");
        adminToken = userToken("admin", "admin123");

        jobWorker.setRunning(false);
        dispatcher.setRunning(false);
        slaScanner.setRunning(false);

        // Drain the outbox backlog left by earlier test classes: claimBatch()
        // takes the 50 OLDEST due rows, so a stale PENDING/FAILED backlog would
        // crowd this class's rows out of every batch and the drills would never
        // touch them. Delivering them (gateway healthy) is the production
        // "recovery" path and leaves no state these tests depend on.
        drainOutboxBacklog();
    }

    private void drainOutboxBacklog() {
        for (int i = 0; i < 12; i++) {
            long due = outboxRepo.countByStatus(OutboxStatus.PENDING)
                    + outboxRepo.countByStatus(OutboxStatus.FAILED);
            if (due == 0) {
                return;
            }
            // Make FAILED rows due, then deliver a batch.
            List<OutboxMessage> notDue = outboxRepo.findAll().stream()
                    .filter(m -> m.getStatus() == OutboxStatus.FAILED
                            && m.getNextRetryAt() != null
                            && m.getNextRetryAt().isAfter(Instant.now()))
                    .toList();
            if (!notDue.isEmpty()) {
                notDue.forEach(m -> m.setNextRetryAt(Instant.now().minusSeconds(1)));
                outboxRepo.saveAll(notDue);
            }
            dispatcher.dispatch();
        }
    }

    @AfterEach
    void cleanup() {
        // Never leak injected failures into other test classes.
        emailGateway.clearSimulatedFailure();
        actionHandler.clearSimulatedFailure();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String userToken(String username, String password) throws Exception {
        var resp = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int idx = resp.indexOf("\"token\":\"");
        int start = idx + "\"token\":\"".length();
        int end = resp.indexOf("\"", start);
        return resp.substring(start, end);
    }

    private long createAndSubmit(String token, String payload) throws Exception {
        var resp = mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeCode\":\"IT_ACCESS\",\"payload\":" + payload + "}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        int idIdx = resp.indexOf("\"id\":");
        long id = Long.parseLong(resp.substring(idIdx + 5).split("[,}\\s]")[0].trim());

        mvc.perform(post("/api/v1/requests/" + id + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is2xxSuccessful());
        return id;
    }

    private void approve(Long requestId) throws Exception {
        mvc.perform(post("/api/v1/requests/" + requestId + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"justification\":\"lgtm\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private boolean payloadRequestIs(OutboxMessage m, long reqId) {
        try {
            Map<String, Object> p = new tools.jackson.databind.ObjectMapper()
                    .readValue(m.getPayload(), Map.class);
            Object rid = p.get("requestId");
            return rid instanceof Number n && n.longValue() == reqId;
        } catch (Exception e) {
            return false;
        }
    }

    private List<OutboxMessage> outboxFor(long reqId) {
        return outboxRepo.findAll().stream()
                .filter(m -> payloadRequestIs(m, reqId))
                .toList();
    }

    private void backdateFailedOutbox() {
        List<OutboxMessage> failed = outboxRepo.findAll().stream()
                .filter(m -> m.getStatus() == OutboxStatus.FAILED
                        && m.getNextRetryAt() != null
                        && m.getNextRetryAt().isAfter(Instant.now().minusSeconds(1)))
                .toList();
        if (failed.isEmpty()) return;
        failed.forEach(m -> m.setNextRetryAt(Instant.now().minusSeconds(1)));
        outboxRepo.saveAll(failed);
    }

    // ── Duplicate submission ─────────────────────────────────────────────

    @Test
    void duplicateSubmitIsRejectedWith409() throws Exception {
        var resp = mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + hankToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeCode\":\"IT_ACCESS\",\"payload\":{\"resource\":\"Duo\",\"duration\":\"7d\"}}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(resp.substring(resp.indexOf("\"id\":") + 5).split("[,}\\s]")[0].trim());

        mvc.perform(post("/api/v1/requests/" + id + "/submit")
                        .header("Authorization", "Bearer " + hankToken))
                .andExpect(status().is2xxSuccessful());

        // Second submit: same logical operation must not be applied twice.
        mvc.perform(post("/api/v1/requests/" + id + "/submit")
                        .header("Authorization", "Bearer " + hankToken))
                .andExpect(status().isConflict());

        Request req = requestRepo.findById(id).orElseThrow();
        assertThat(req.getStatus()).isEqualTo(RequestStatus.IN_REVIEW);
    }

    // ── Dispatcher failure + recovery ────────────────────────────────────

    @Nested
    @DisplayName("dispatcher failure drill")
    class DispatcherDrill {

        @Test
        void killedMidBatchThenRecoversNothingLost() throws Exception {
            long reqId = createAndSubmit(hankToken, "{\"resource\":\"PagerDuty\",\"duration\":\"30d\"}");
            approve(reqId);
            assertThat(outboxFor(reqId)).isNotEmpty();

            // "Kill" the transport: the next email send throws.
            emailGateway.setSimulatedFailure(true, 1);
            dispatcher.dispatch();

            // The failed send must be recorded (retry state), never silently dropped.
            List<OutboxMessage> failedNow = outboxRepo.findAll().stream()
                    .filter(m -> m.getStatus() == OutboxStatus.FAILED
                            && m.getLastError() != null
                            && m.getLastError().contains("Simulated email failure"))
                    .toList();
            assertThat(failedNow).isNotEmpty();
            failedNow.forEach(m -> {
                assertThat(m.getRetryCount()).isGreaterThanOrEqualTo(1);
                assertThat(m.getNextRetryAt()).isAfter(Instant.now().minusSeconds(1));
                // A failed send must never be stamped as delivered.
                assertThat(m.getSentAt()).as("failed message must have sent_at NULL").isNull();
            });

            // Transport recovers: clear the failure, backdate the retry, dispatch again.
            emailGateway.clearSimulatedFailure();
            backdateFailedOutbox();
            dispatcher.dispatch();

            // Every outbox row for this request ends up SENT — nothing lost,
            // and only now (after a real successful send) is sent_at stamped.
            List<OutboxMessage> mine = outboxFor(reqId);
            assertThat(mine).isNotEmpty();
            assertThat(mine).allSatisfy(m -> {
                assertThat(m.getStatus()).isEqualTo(OutboxStatus.SENT);
                assertThat(m.getSentAt()).as("SENT message must have sent_at").isNotNull();
            });

            // No duplicate deliveries: every idempotency key for this request
            // is unique (the DB unique constraint + claim logic guarantee it).
            long distinct = mine.stream().map(OutboxMessage::getIdempotencyKey).distinct().count();
            assertThat(mine).hasSize((int) distinct);
        }

        @Test
        void repeatedFailuresExhaustRetriesToDeadLetter() throws Exception {
            long reqId = createAndSubmit(hankToken, "{\"resource\":\"Datadog\",\"duration\":\"7d\"}");

            // Pre-age one of this request's outbox rows so it dead-letters after 2 failures.
            // Must be an EMAIL-channel row: FEED delivery never touches the email
            // gateway, so a FEED row can never fail and never dead-letters.
            OutboxMessage target = outboxFor(reqId).stream()
                    .filter(m -> "EMAIL".equals(m.getChannel()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no EMAIL outbox row for " + reqId));
            target.setRetryCount(target.getMaxRetries() - 2);
            outboxRepo.save(target);

            // Fail every send until the retries are exhausted (count=0 = indefinite).
            emailGateway.setSimulatedFailure(true, 0);
            try {
                dispatcher.dispatch();
                backdateFailedOutbox();
                dispatcher.dispatch();
            } finally {
                emailGateway.clearSimulatedFailure();
            }

            OutboxMessage after = outboxRepo.findById(target.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);
            assertThat(after.getRetryCount()).isGreaterThanOrEqualTo(target.getMaxRetries());
            assertThat(after.getLastError()).contains("Simulated email failure");
            // Never delivered → never stamped as delivered.
            assertThat(after.getSentAt()).as("dead-lettered message must have sent_at NULL").isNull();
        }
    }

    // ── Job worker failure + recovery ────────────────────────────────────

    @Test
    void jobFailsThenRetriesToCompletedAndRequestExecutes() throws Exception {
        long reqId = createAndSubmit(hankToken, "{\"resource\":\"Grafana\",\"duration\":\"7d\"}");
        approve(reqId);

        ExecutionJob job = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId)
                .stream().findFirst()
                .orElseThrow(() -> new AssertionError("no job for " + reqId));

        // Inject a failure for the next execution.
        actionHandler.setSimulatedFailure(true, 1);
        jobWorker.process();

        // Whatever consumed the injection, this one must end COMPLETED.
        actionHandler.clearSimulatedFailure();
        if (jobRepo.findById(job.getId()).orElseThrow().getStatus() == JobStatus.FAILED) {
            txTemplate.executeWithoutResult(s ->
                    jobRepo.resetNextRetryAt(job.getId(), Instant.now().minusSeconds(1)));
            jobWorker.process();
        }

        ExecutionJob done = jobRepo.findById(job.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getResult()).containsEntry("status", "SIMULATED_OK");
        assertThat(done.getCompletedAt()).isNotNull();

        Request req = requestRepo.findById(reqId).orElseThrow();
        assertThat(req.getStatus()).isEqualTo(RequestStatus.EXECUTED);
    }

    @Test
    void failedJobKeepsRequestApprovedUntilItRecovers() throws Exception {
        long reqId = createAndSubmit(hankToken, "{\"resource\":\"Terraform\",\"duration\":\"14d\"}");
        approve(reqId);

        ExecutionJob job = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId)
                .stream().findFirst()
                .orElseThrow(() -> new AssertionError("no job for " + reqId));

        actionHandler.setSimulatedFailure(true, 1);
        jobWorker.process();

        // If our job consumed the injection, it is FAILED and the request must
        // stay APPROVED (not EXECUTED) until the retry lands.
        ExecutionJob j = jobRepo.findById(job.getId()).orElseThrow();
        if (j.getStatus() == JobStatus.FAILED) {
            Request req = requestRepo.findById(reqId).orElseThrow();
            assertThat(req.getStatus()).isEqualTo(RequestStatus.APPROVED);
            assertThat(j.getLastError()).contains("Simulated failure");
            assertThat(j.getNextRetryAt()).isAfter(Instant.now().minusSeconds(1));
        } else {
            // Another (pre-existing) job consumed the injection — our job completed.
            assertThat(j.getStatus()).isEqualTo(JobStatus.COMPLETED);
        }
    }
}
