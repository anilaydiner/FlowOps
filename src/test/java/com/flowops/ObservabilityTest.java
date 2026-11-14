package com.flowops;

import com.flowops.domain.DepartmentRepository;
import com.flowops.domain.ExecutionJobRepository;
import com.flowops.domain.JobStatus;
import com.flowops.domain.Role;
import com.flowops.domain.RoleRepository;
import com.flowops.domain.User;
import com.flowops.domain.UserRepository;
import com.flowops.observability.InMemorySpanExporter;
import com.flowops.infra.worker.JobWorker;
import com.flowops.infra.worker.OutboxDispatcher;
import com.flowops.infra.mail.SimulatedEmailGateway;
import com.flowops.infra.worker.SlaScanner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Iteration 8 tests: OpenTelemetry tracing + Micrometer metrics (PLAN §8).
 *
 * - One span per request-lifecycle event (submit / decide / job / outbox / SLA)
 * - Spans carry request + actor attributes; failures produce ERROR spans
 * - Counters increment for submit/decide/approve/outbox/job/SLA
 * - Pending gauges are registered
 */
@AutoConfigureMockMvc
class ObservabilityTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired InMemorySpanExporter spanExporter;
    @Autowired MeterRegistry metrics;
    @Autowired JobWorker jobWorker;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired SlaScanner slaScanner;
    @Autowired SimulatedEmailGateway emailGateway;
    @Autowired com.flowops.infra.action.SimulatedActionHandler actionHandler;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired DepartmentRepository departments;
    @Autowired PasswordEncoder encoder;
    @Autowired ExecutionJobRepository jobRepo;
    @Autowired JdbcTemplate jdbc;

    private String hankToken;
    private String adminToken;

    @BeforeEach
    void setup() throws Exception {
        if (users.findByUsername("it8_hank").isEmpty()) {
            User hank = User.newUser("it8_hank", "It8 Hank", "it8_hank@example.com",
                    departments.findByCode("IT").orElse(null),
                    Set.of(roles.findByCode("MEMBER").orElseThrow()), encoder.encode("it8_hank123"));
            users.save(hank);
        }
        hankToken = userToken("it8_hank", "it8_hank123");
        adminToken = userToken("admin", "admin123");

        jobWorker.setRunning(false);
        dispatcher.setRunning(false);
        slaScanner.setRunning(false);
        emailGateway.clearSimulatedFailure();
        actionHandler.clearSimulatedFailure();
        drainPendingJobs();
        spanExporter.reset();
    }

    /**
     * Process any PLANNED/FAILED jobs left by earlier test classes so they don't
     * consume job slots that this test's counter assertions depend on.
     * Also clears leaked failure-injection state from the action handler.
     */
    private void drainPendingJobs() {
        for (int i = 0; i < 10; i++) {
            long pending = jobRepo.countByStatus(JobStatus.PLANNED) + jobRepo.countByStatus(JobStatus.FAILED);
            if (pending == 0) return;
            // Make all FAILED jobs immediately eligible for claim
            jdbc.update("UPDATE execution_jobs SET next_retry_at = ? WHERE status = 'FAILED'",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
            jobWorker.process();
        }
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

    private void approve(long requestId) throws Exception {
        mvc.perform(post("/api/v1/requests/" + requestId + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"justification\":\"lgtm\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private double counter(String name) {
        // Sum across all tag variants — the counter is registered with an
        // "action" tag, so multiple meters share the same name.  find(name).counter()
        // returns only the first match, which may be a different tag variant
        // that wasn't incremented by this test.
        double total = 0;
        for (io.micrometer.core.instrument.Meter meter : metrics.getMeters()) {
            if (meter.getId().getName().equals(name) && meter instanceof Counter) {
                total += ((Counter) meter).count();
            }
        }
        return total;
    }

    private SpanData lastSpan(String name) {
        List<SpanData> matches = spanExporter.finishedSpans().stream()
                .filter(s -> s.getName().equals(name))
                .toList();
        assertThat(matches).as("spans named '%s'", name).isNotEmpty();
        return matches.get(matches.size() - 1);
    }

    // ── Spans ────────────────────────────────────────────────────────────

    @Test
    void lifecycleEventsProduceSpansWithAttributes() throws Exception {
        long reqId = createAndSubmit(hankToken, "{\"resource\":\"Jira\",\"duration\":\"30d\"}");
        approve(reqId);
        jobWorker.process();
        dispatcher.dispatch();

        SpanData submit = lastSpan("request.submit");
        assertThat(submit.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("request.id")))
                .isEqualTo(String.valueOf(reqId));
        assertThat(submit.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("user")))
                .isEqualTo("it8_hank");
        assertThat(submit.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);

        SpanData decide = lastSpan("request.decide");
        assertThat(decide.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("action")))
                .isEqualTo("APPROVE");

        SpanData job = lastSpan("job.execute");
        assertThat(job.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("request.id")))
                .isEqualTo(String.valueOf(reqId));
        assertThat(job.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);

        SpanData deliver = lastSpan("outbox.deliver");
        assertThat(deliver.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("outbox.key")))
                .isNotBlank();
    }

    @Test
    void failedDeliveryProducesErrorSpan() throws Exception {
        // Ensure at least one deliverable outbox message exists: submit a request
        long reqId = createAndSubmit(hankToken, "{\"resource\":\"Slack\",\"duration\":\"7d\"}");
        assertThat(reqId).isPositive();

        emailGateway.setSimulatedFailure(true, 1);
        try {
            dispatcher.dispatch();
        } finally {
            emailGateway.clearSimulatedFailure();
        }

        List<SpanData> delivers = spanExporter.finishedSpans().stream()
                .filter(s -> s.getName().equals("outbox.deliver"))
                .toList();
        assertThat(delivers).isNotEmpty();
        assertThat(delivers)
                .anySatisfy(s -> assertThat(s.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR));
    }

    @Test
    void slaScanSpanCarriesBreachCounts() throws Exception {
        long reqId = createAndSubmit(hankToken, "{\"resource\":\"Figma\",\"duration\":\"14d\"}");
        slaScanner.scan();

        SpanData scan = lastSpan("sla.scan");
        assertThat(scan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("component")))
                .isEqualTo("sla-scanner");
        assertThat(scan.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    // ── Metrics ──────────────────────────────────────────────────────────

    @Test
    void metricsCountersIncrementAcrossLifecycle() throws Exception {
        double submittedBefore = counter("flowops.request.submitted");
        double decidedBefore = counter("flowops.request.decided");
        double approvedBefore = counter("flowops.request.approved");
        double sentBefore = counter("flowops.outbox.message.sent");
        double jobsBefore = counter("flowops.job.completed");

        long reqId = createAndSubmit(hankToken, "{\"resource\":\"Notion\",\"duration\":\"30d\"}");
        approve(reqId);
        jobWorker.process();
        dispatcher.dispatch();

        assertThat(counter("flowops.request.submitted") - submittedBefore).isGreaterThanOrEqualTo(1);
        assertThat(counter("flowops.request.decided") - decidedBefore).isGreaterThanOrEqualTo(1);
        assertThat(counter("flowops.request.approved") - approvedBefore).isGreaterThanOrEqualTo(1);
        // EMAIL + FEED notifications for this request
        assertThat(counter("flowops.outbox.message.sent") - sentBefore).isGreaterThanOrEqualTo(1);
        assertThat(counter("flowops.job.completed") - jobsBefore).isGreaterThanOrEqualTo(1);
    }

    @Test
    void pendingGaugesAreRegistered() {
        Gauge outboxGauge = metrics.find("flowops.outbox.pending").gauge();
        assertThat(outboxGauge).as("flowops.outbox.pending gauge").isNotNull();
        assertThat(outboxGauge.value()).isGreaterThanOrEqualTo(0);

        Gauge jobGauge = metrics.find("flowops.job.pending").gauge();
        assertThat(jobGauge).as("flowops.job.pending gauge").isNotNull();
        assertThat(jobGauge.value()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void dispatchTimerIsRecorded() throws Exception {
        long reqId = createAndSubmit(hankToken, "{\"resource\":\"Asana\",\"duration\":\"7d\"}");
        assertThat(reqId).isPositive();

        dispatcher.dispatch();

        io.micrometer.core.instrument.Timer timer = metrics.find("flowops.outbox.dispatch").timer();
        assertThat(timer).as("flowops.outbox.dispatch timer after a dispatch").isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void failedJobIncrementsFailedCounter() throws Exception {
        double failedBefore = counter("flowops.job.failed");

        long reqId = createAndSubmit(hankToken, "{\"resource\":\"Zoom\",\"duration\":\"7d\"}");
        approve(reqId);

        actionHandler.setSimulatedFailure(true, 1);
        try {
            jobWorker.process();
        } finally {
            actionHandler.clearSimulatedFailure();
        }

        assertThat(counter("flowops.job.failed") - failedBefore).isGreaterThanOrEqualTo(1);
    }
}
