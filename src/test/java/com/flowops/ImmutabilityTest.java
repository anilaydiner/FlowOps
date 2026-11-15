package com.flowops;

import com.flowops.domain.Clarification;
import com.flowops.infra.persistence.ClarificationJpaRepository;
import com.flowops.infra.persistence.DecisionJpaRepository;
import com.flowops.infra.persistence.DepartmentJpaRepository;
import com.flowops.domain.ExecutionJob;
import com.flowops.infra.persistence.ExecutionJobJpaRepository;
import com.flowops.domain.Role;
import com.flowops.infra.persistence.RoleJpaRepository;
import com.flowops.domain.User;
import com.flowops.infra.persistence.UserJpaRepository;
import com.flowops.infra.worker.JobWorker;
import com.flowops.infra.worker.OutboxDispatcher;
import com.flowops.infra.worker.SlaScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Iteration 8 tests: audit immutability enforced at the database level (PLAN §8).
 *
 * <p>PostgreSQL triggers (V9) make the audit trail append-only so that no
 * client, admin, or application bug can rewrite history:
 *
 * <ul>
 *   <li>{@code decisions} — fully immutable (no UPDATE, no DELETE)</li>
 *   <li>{@code execution_jobs} — identity immutable (key/request/action/
 *       created_at/correlation_id); lifecycle columns may evolve; no DELETE</li>
 *   <li>{@code clarifications} — question/asked_by/request_id/created_at
 *       immutable; the answer may be recorded once; no DELETE</li>
 * </ul>
 */
@AutoConfigureMockMvc
class ImmutabilityTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JobWorker jobWorker;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired SlaScanner slaScanner;
    @Autowired UserJpaRepository users;
    @Autowired RoleJpaRepository roles;
    @Autowired DepartmentJpaRepository departments;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate txTemplate;
    @Autowired DecisionJpaRepository decisionRepo;
    @Autowired ExecutionJobJpaRepository jobRepo;
    @Autowired ClarificationJpaRepository clarificationRepo;

    private String hankToken;
    private String adminToken;
    private long hankId;
    private long reqId;
    private long decisionId;
    private long jobId;
    private long clarificationId;

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

        // One request in APPROVED state: a decision row, a job row, a clarification row.
        reqId = createAndSubmit(hankToken, "{\"resource\":\"Audit-Imm\",\"duration\":\"7d\"}");
        approve(reqId);

        decisionId = decisionRepo.findAll().stream()
                .map(d -> d.getId())
                .max(Long::compareTo)
                .orElseThrow(() -> new AssertionError("no decision rows"));

        ExecutionJob job = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId)
                .stream().findFirst()
                .orElseThrow(() -> new AssertionError("no execution job for " + reqId));
        jobId = job.getId();

        Clarification c = clarificationRepo.save(new Clarification(reqId, hankId, "Any constraints?"));
        clarificationId = c.getId();
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

    // ── decisions: fully immutable ───────────────────────────────────────

    @Nested
    @DisplayName("decisions")
    class Decisions {

        @Test
        void updateIsBlocked() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("UPDATE decisions SET justification = 'tampered' WHERE id = ?", decisionId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }

        @Test
        void deleteIsBlocked() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("DELETE FROM decisions WHERE id = ?", decisionId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }

        @Test
        void rowIsUntouchedAfterBlockedUpdate() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("UPDATE decisions SET justification = 'tampered' WHERE id = ?", decisionId)))
                    .isInstanceOf(DataAccessException.class);

            var d = decisionRepo.findById(decisionId).orElseThrow();
            assertThat(d.getJustification()).isEqualTo("lgtm");
        }
    }

    // ── execution_jobs: identity immutable, lifecycle mutable ────────────

    @Nested
    @DisplayName("execution_jobs")
    class ExecutionJobs {

        @Test
        void deleteIsBlocked() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("DELETE FROM execution_jobs WHERE id = ?", jobId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }

        @Test
        void idempotencyKeyChangeIsBlocked() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("UPDATE execution_jobs SET idempotency_key = 'req:999:HACK' WHERE id = ?", jobId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("identity is immutable");
        }

        @Test
        void actionChangeIsBlocked() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("UPDATE execution_jobs SET action = 'PURCHASE_TICKET' WHERE id = ?", jobId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("identity is immutable");
        }

        @Test
        void createdAtChangeIsBlocked() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("UPDATE execution_jobs SET created_at = now() - interval '1 day' WHERE id = ?", jobId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("identity is immutable");
        }

        @Test
        void lifecycleColumnsMayEvolve() {
            // status + retry bookkeeping are the job's lifecycle — allowed.
            int updated = txTemplate.execute(s ->
                    jdbc.update("UPDATE execution_jobs SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                            java.sql.Timestamp.from(Instant.now()), jobId));
            assertThat(updated).isEqualTo(1);

            var j = jobRepo.findById(jobId).orElseThrow();
            assertThat(j.getStatus().name()).isEqualTo("COMPLETED");
        }
    }

    // ── clarifications: identity immutable, answer mutable ───────────────

    @Nested
    @DisplayName("clarifications")
    class Clarifications {

        @Test
        void deleteIsBlocked() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("DELETE FROM clarifications WHERE id = ?", clarificationId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }

        @Test
        void questionChangeIsBlocked() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("UPDATE clarifications SET question = 'changed' WHERE id = ?", clarificationId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("identity is immutable");
        }

        @Test
        void askedByChangeIsBlocked() {
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                    jdbc.update("UPDATE clarifications SET asked_by = 1 WHERE id = ?", clarificationId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("identity is immutable");
        }

        @Test
        void answerMayBeRecorded() {
            int updated = txTemplate.execute(s ->
                    jdbc.update("UPDATE clarifications SET answer = 'none', answered_at = ? WHERE id = ?",
                            java.sql.Timestamp.from(Instant.now()), clarificationId));
            assertThat(updated).isEqualTo(1);

            var c = clarificationRepo.findById(clarificationId).orElseThrow();
            assertThat(c.getAnswer()).isEqualTo("none");
        }
    }
}
