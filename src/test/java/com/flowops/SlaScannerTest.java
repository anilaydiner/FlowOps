package com.flowops;

import com.flowops.domain.*;
import com.flowops.service.OutboxDispatcher;
import com.flowops.service.SlaScanner;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Iteration 7 tests: SLA Scanner (PLAN §8).
 *
 * Verifies:
 * - SLA breach → escalation notification via outbox
 * - SLA expiry (2× SLA) → request transitions to EXPIRED
 * - Steps without SLA → no breach
 * - Idempotent SLA notifications (no duplicates)
 */
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SlaScannerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired SlaScanner slaScanner;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired StepInstanceRepository stepRepo;
    @Autowired WorkflowStepRepository stepDefRepo;
    @Autowired RequestRepository requestRepo;
    @Autowired OutboxMessageRepository outboxRepo;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired DepartmentRepository departments;
    @Autowired PasswordEncoder encoder;
    @Autowired RequestTypeRepository typeRepo;
    @Autowired WorkflowRepository workflowRepo;
    @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate;

    private String bobToken;
    private String adminToken;

    @BeforeEach
    void setup() throws Exception {
        if (users.findByUsername("it7_bob").isEmpty()) {
            Set<Role> memberRoles = Set.of(roles.findByCode("MEMBER").orElseThrow());
            User bob = User.newUser("it7_bob", "It7 Bob", "it7_bob@example.com",
                    departments.findByCode("FINANCE").orElse(null), memberRoles, encoder.encode("it7_bob123"));
            users.save(bob);
        }
        bobToken = userToken("it7_bob", "it7_bob123");
        adminToken = userToken("admin", "admin123");

        slaScanner.setRunning(false);
        dispatcher.setRunning(false);
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

    private long createAndSubmit(String token, String typeCode, String payload) throws Exception {
        var resp = mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeCode\":\"" + typeCode + "\",\"payload\":" + payload + "}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        int idIdx = resp.indexOf("\"id\":");
        long id = Long.parseLong(resp.substring(idIdx + 5).split("[,}\\s]")[0].trim());

        mvc.perform(post("/api/v1/requests/" + id + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is2xxSuccessful());
        return id;
    }

    /** Set SLA on the first step of the request's workflow, then backdate the step instance. */
    private void setupSla(long requestId, int slaMinutes) {
        setupSla(requestId, slaMinutes, (long) slaMinutes * 60 + 120);
    }

    /** Set SLA + explicit backdate (seconds ago). */
    private void setupSla(long requestId, int slaMinutes, long backdateSeconds) {
        Request request = requestRepo.findById(requestId).orElseThrow();
        Long workflowId = request.getType().getDefaultWorkflowId();
        List<WorkflowStep> steps = stepDefRepo.findByWorkflowIdOrderById(workflowId);
        WorkflowStep firstStep = steps.get(0);
        firstStep.configure(null, 0, null, slaMinutes, null, false);
        stepDefRepo.save(firstStep);

        // Backdate the step instance to simulate elapsed time
        StepInstance si = stepRepo.findTopByRequestIdAndStateOrderById(requestId, StepState.PENDING).orElseThrow();
        Instant pastTs = Instant.now().minusSeconds(backdateSeconds);
        txTemplate.executeWithoutResult(status -> stepRepo.backdateCreatedAt(si.getId(), pastTs));
    }

    // ── SLA breach ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("SLA breach")
    class SlaBreach {

        @Test
        @Order(1)
        void breachSendsEscalationNotification() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Jira\",\"duration\":\"30d\"}");

            // Set 1-minute SLA and backdate the step instance to 3 minutes ago
            setupSla(reqId, 1);

            // Run the scanner
            slaScanner.scan();

            // SLA breach notification should be in the outbox
            List<OutboxMessage> slaMessages = outboxRepo.findAll().stream()
                    .filter(m -> m.getIdempotencyKey().contains(":sla:"))
                    .toList();
            assertThat(slaMessages).isNotEmpty();
            assertThat(slaMessages.get(0).getChannel()).isEqualTo("EMAIL");

            // Request should still be IN_REVIEW (not yet expired — 1min SLA, 3min elapsed < 2min expiry)
            // Wait: expiry is 2× SLA = 2min. 3min > 2min, so it SHOULD be expired.
            // Let me adjust: use a larger SLA to test breach without expiry.
        }

        @Test
        @Order(2)
        void breachWithoutExpiry() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Slack\",\"duration\":\"14d\"}");

            // Set 10-minute SLA, backdate to 15 minutes ago
            // Breach: 15 > 10 ✓
            // Expiry: 15 < 20 (2×10) ✓ → NOT expired
            setupSla(reqId, 10, 15 * 60L);

            slaScanner.scan();

            // Breach notification sent
            List<OutboxMessage> slaMessages = outboxRepo.findAll().stream()
                    .filter(m -> m.getIdempotencyKey().contains(":sla:"))
                    .toList();
            assertThat(slaMessages).isNotEmpty();

            // Request still IN_REVIEW (not expired)
            Request req = requestRepo.findById(reqId).orElseThrow();
            assertThat(req.getStatus()).isEqualTo(RequestStatus.IN_REVIEW);
        }

        @Test
        @Order(3)
        void slaNotificationsAreIdempotent() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Figma\",\"duration\":\"30d\"}");

            setupSla(reqId, 10);

            // Scan twice — should not duplicate notifications
            slaScanner.scan();
            int afterFirst = outboxRepo.findAll().stream()
                    .filter(m -> m.getIdempotencyKey().contains(":sla:"))
                    .toList().size();

            slaScanner.scan();
            int afterSecond = outboxRepo.findAll().stream()
                    .filter(m -> m.getIdempotencyKey().contains(":sla:"))
                    .toList().size();

            assertThat(afterSecond).isEqualTo(afterFirst);
        }
    }

    // ── SLA expiry ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("SLA expiry")
    class SlaExpiry {

        @Test
        @Order(1)
        void expiredWhenBeyond2xSla() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Notion\",\"duration\":\"7d\"}");

            // Set 5-minute SLA, backdate to 15 minutes ago (3× SLA)
            // Breach: 15 > 5 ✓
            // Expiry: 15 > 10 (2×5) ✓ → EXPIRED
            setupSla(reqId, 5, 15 * 60L);

            slaScanner.scan();

            Request req = requestRepo.findById(reqId).orElseThrow();
            assertThat(req.getStatus()).isEqualTo(RequestStatus.EXPIRED);
        }

        @Test
        @Order(2)
        void noSlaMeansNoBreach() throws Exception {
            long reqId = createAndSubmit(bobToken, "POLICY_EXCEPTION",
                    "{\"policy\":\"remote work policy\",\"justification\":\"client visit\"}");

            // POLICY_EXCEPTION workflow steps have no SLA (null slaMinutes)
            // Backdate the step instance to 1000 minutes ago
            StepInstance si = stepRepo.findTopByRequestIdAndStateOrderById(reqId, StepState.PENDING).orElseThrow();
            txTemplate.executeWithoutResult(status ->
                    stepRepo.backdateCreatedAt(si.getId(), Instant.now().minusSeconds(60000)));

            slaScanner.scan();

            // Request should still be IN_REVIEW (no SLA declared)
            Request req = requestRepo.findById(reqId).orElseThrow();
            assertThat(req.getStatus()).isEqualTo(RequestStatus.IN_REVIEW);
        }
    }
}
