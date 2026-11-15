package com.flowops;

import com.flowops.domain.AuditEvent;
import com.flowops.domain.*;
import com.flowops.infra.persistence.*;
import com.flowops.infra.worker.JobWorker;
import com.flowops.infra.worker.OutboxDispatcher;
import com.flowops.infra.worker.SlaScanner;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Iteration 8 tests: queryable audit timeline (PLAN §8).
 *
 * - Full timeline: STATUS + SUBMITTED + DECISION + JOB events, chronological
 * - `type` filter (entity)
 * - `from`/`to` time-window filter
 * - 400 on unknown type, 404 for non-owner
 * - SLA breach events appear after a scanner run
 */
@AutoConfigureMockMvc
class AuditApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JobWorker jobWorker;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired SlaScanner slaScanner;
    @Autowired UserJpaRepository users;
    @Autowired RoleJpaRepository roles;
    @Autowired DepartmentJpaRepository departments;
    @Autowired PasswordEncoder encoder;
    @Autowired RequestJpaRepository requestRepo;
    @Autowired StepInstanceJpaRepository stepRepo;
    @Autowired WorkflowStepJpaRepository stepDefRepo;
    @Autowired TransactionTemplate txTemplate;

    private String hankToken;
    private String ivyToken;
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
        if (users.findByUsername("it8_ivy").isEmpty()) {
            User ivy = User.newUser("it8_ivy", "It8 Ivy", "it8_ivy@example.com",
                    departments.findByCode("HR").orElse(null),
                    Set.of(roles.findByCode("MEMBER").orElseThrow()), encoder.encode("it8_ivy123"));
            users.save(ivy);
        }
        hankId = users.findByUsername("it8_hank").orElseThrow().getId();

        hankToken = userToken("it8_hank", "it8_hank123");
        ivyToken = userToken("it8_ivy", "it8_ivy123");
        adminToken = userToken("admin", "admin123");

        jobWorker.setRunning(false);
        dispatcher.setRunning(false);
        slaScanner.setRunning(false);
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

    private void approve(Long requestId) throws Exception {
        mvc.perform(post("/api/v1/requests/" + requestId + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"justification\":\"lgtm\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private List<AuditEvent> audit(long reqId, String token) throws Exception {
        String body = mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readValue(
                body, new tools.jackson.core.type.TypeReference<List<AuditEvent>>() {});
    }

    // ── Timeline contents ────────────────────────────────────────────────

    @Test
    void fullTimelineCoversLifecycle() throws Exception {
        long reqId = createAndSubmit(hankToken, "IT_ACCESS", "{\"resource\":\"Jira\",\"duration\":\"30d\"}");
        approve(reqId);
        jobWorker.process();

        List<AuditEvent> events = audit(reqId, hankToken);

        // Chronological (non-decreasing timestamps)
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).at()).isAfterOrEqualTo(events.get(i - 1).at());
        }

        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("STATUS");
            assertThat(e.summary()).contains("created");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("SUBMITTED");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("DECISION");
            assertThat(e.actor()).isEqualTo("admin");
            assertThat(e.details().get("action")).isEqualTo("APPROVE");
            assertThat(e.details().get("step")).isNotNull();
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("JOB");
            assertThat(e.summary()).contains("PROVISIONING");
            assertThat(e.summary()).contains("enqueued");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("JOB");
            assertThat(e.details().get("status")).isEqualTo("COMPLETED");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("STATUS");
            assertThat(e.summary()).contains("EXECUTED");
        });
    }

    // ── Filters ─────────────────────────────────────────────────────────

    @Test
    void typeFilterRestrictsToEntity() throws Exception {
        long reqId = createAndSubmit(hankToken, "IT_ACCESS", "{\"resource\":\"Slack\",\"duration\":\"7d\"}");
        approve(reqId);
        jobWorker.process();

        String jobsOnly = mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                        .param("type", "JOB")
                        .header("Authorization", "Bearer " + hankToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<AuditEvent> jobEvents = new tools.jackson.databind.ObjectMapper().readValue(
                jobsOnly, new tools.jackson.core.type.TypeReference<List<AuditEvent>>() {});

        assertThat(jobEvents).isNotEmpty();
        assertThat(jobEvents).allSatisfy(e -> assertThat(e.type()).isEqualTo("JOB"));
    }

    @Test
    void timeWindowFilters() throws Exception {
        long reqId = createAndSubmit(hankToken, "IT_ACCESS", "{\"resource\":\"Figma\",\"duration\":\"14d\"}");
        java.time.Instant windowStart = java.time.Instant.now(); // decisions happen after this
        approve(reqId);

        // `from` after submit → SUBMITTED excluded, DECISION included
        String fromBody = mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                        .param("from", windowStart.toString())
                        .header("Authorization", "Bearer " + hankToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<AuditEvent> fromEvents = new tools.jackson.databind.ObjectMapper().readValue(
                fromBody, new tools.jackson.core.type.TypeReference<List<AuditEvent>>() {});
        assertThat(fromEvents).noneSatisfy(e -> assertThat(e.type()).isEqualTo("SUBMITTED"));
        assertThat(fromEvents).anySatisfy(e -> assertThat(e.type()).isEqualTo("DECISION"));

        // `to` before the decision → only early events
        String toBody = mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                        .param("to", windowStart.toString())
                        .header("Authorization", "Bearer " + hankToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<AuditEvent> toEvents = new tools.jackson.databind.ObjectMapper().readValue(
                toBody, new tools.jackson.core.type.TypeReference<List<AuditEvent>>() {});
        assertThat(toEvents).noneSatisfy(e -> assertThat(e.type()).isEqualTo("DECISION"));
        assertThat(toEvents).anySatisfy(e -> assertThat(e.type()).isEqualTo("SUBMITTED"));
    }

    @Test
    void invalidTypeReturns400() throws Exception {
        long reqId = createAndSubmit(hankToken, "IT_ACCESS", "{\"resource\":\"Notion\",\"duration\":\"7d\"}");
        mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                        .param("type", "BOGUS")
                        .header("Authorization", "Bearer " + hankToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonOwnerGets403() throws Exception {
        long reqId = createAndSubmit(hankToken, "IT_ACCESS", "{\"resource\":\"Zoom\",\"duration\":\"7d\"}");
        mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                        .header("Authorization", "Bearer " + ivyToken))
                .andExpect(status().isForbidden());

        // admin can read anyone's audit trail
        mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void slaBreachAppearsInTimeline() throws Exception {
        long reqId = createAndSubmit(hankToken, "IT_ACCESS", "{\"resource\":\"Asana\",\"duration\":\"7d\"}");

        // SLA on first step + backdate (same pattern as SlaScannerTest)
        Request request = requestRepo.findById(reqId).orElseThrow();
        List<WorkflowStep> steps = stepDefRepo.findByWorkflowIdOrderById(request.getType().getDefaultWorkflowId());
        WorkflowStep first = steps.get(0);
        first.configure(null, 0, null, 10, null, false);
        stepDefRepo.save(first);
        try {
            StepInstance si = stepRepo.findTopByRequestIdAndStateOrderById(reqId, StepState.PENDING).orElseThrow();
            txTemplate.executeWithoutResult(s -> stepRepo.backdateCreatedAt(si.getId(),
                    java.time.Instant.now().minusSeconds(15 * 60L)));

            slaScanner.scan();

            List<AuditEvent> slaEvents = new tools.jackson.databind.ObjectMapper().readValue(
                    mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                                    .param("type", "SLA")
                                    .header("Authorization", "Bearer " + hankToken))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString(),
                    new tools.jackson.core.type.TypeReference<List<AuditEvent>>() {});
            assertThat(slaEvents).isNotEmpty();
            assertThat(slaEvents).allSatisfy(e -> assertThat(e.type()).isEqualTo("SLA"));
            assertThat(slaEvents.get(0).summary()).contains("SLA breach");
        } finally {
            // Reset the shared workflow step so other tests see no SLA
            first.configure(null, 0, null, null, null, false);
            stepDefRepo.save(first);
        }
    }

    // ── audit:read:any permission (Scenario 5) ──────────────────────────

    @Nested
    @org.junit.jupiter.api.DisplayName("audit:read:any permission (Scenario 5)")
    class AuditPermission {

        private String auditorToken;
        private String ivyToken; // MEMBER, no audit permission

        @org.junit.jupiter.api.BeforeEach
        void setupAuditor() throws Exception {
            if (users.findByUsername("it8_auditor").isEmpty()) {
                com.flowops.domain.Role auditorRole = roles.findByCode("AUDITOR")
                        .orElseThrow(() -> new IllegalStateException("AUDITOR role not seeded"));
                User auditor = User.newUser("it8_auditor", "It8 Auditor", "it8_auditor@example.com",
                        null, Set.of(auditorRole), encoder.encode("it8_audit123"));
                users.save(auditor);
            }
            auditorToken = userToken("it8_auditor", "it8_audit123");
            ivyToken = userToken("it8_ivy", "it8_ivy123");
        }

        @Test
        @org.junit.jupiter.api.DisplayName("auditor CAN read any request's audit log")
        void auditor_canReadAnyAudit() throws Exception {
            long reqId = createAndSubmit(hankToken, "IT_ACCESS", "{\"resource\":\"Jira\",\"duration\":\"30d\"}");

            mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                            .header("Authorization", "Bearer " + hankToken))
                    .andExpect(status().isOk());

            mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                            .header("Authorization", "Bearer " + auditorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());

            mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("user WITHOUT audit:read:any CANNOT read others' audit log")
        void nonAuditor_cannotReadOthersAudit() throws Exception {
            long reqId = createAndSubmit(hankToken, "IT_ACCESS", "{\"resource\":\"Slack\",\"duration\":\"7d\"}");

            mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                            .header("Authorization", "Bearer " + ivyToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("owner can still read own audit log without audit:read:any")
        void owner_canReadOwnAudit() throws Exception {
            long reqId = createAndSubmit(hankToken, "IT_ACCESS", "{\"resource\":\"Figma\",\"duration\":\"14d\"}");

            mvc.perform(get("/api/v1/requests/" + reqId + "/audit")
                            .header("Authorization", "Bearer " + hankToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }
}
