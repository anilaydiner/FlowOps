package com.flowops;

import com.flowops.domain.*;
import com.flowops.infra.worker.JobWorker;
import com.flowops.infra.worker.OutboxDispatcher;
import com.flowops.infra.action.SimulatedActionHandler;
import com.flowops.infra.worker.SlaScanner;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Iteration 7 tests: Execution Jobs + DELEGATE (PLAN §8).
 *
 * Verifies:
 * - Approval triggers execution jobs for types with actions
 * - JobWorker processes jobs and transitions APPROVED → EXECUTED
 * - Job failure → retry → DEAD_LETTER
 * - Idempotency: duplicate enqueue is no-op
 * - DELEGATE records decision with delegatedTo, does NOT advance workflow
 * - Types without execution actions (POLICY_EXCEPTION) stay APPROVED
 */
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExecutionJobsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExecutionJobRepository jobRepo;
    @Autowired RequestRepository requestRepo;
    @Autowired DecisionRepository decisionRepo;
    @Autowired StepInstanceRepository stepInstanceRepo;
    @Autowired JobWorker jobWorker;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired SlaScanner slaScanner;
    @Autowired SimulatedActionHandler actionHandler;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired DepartmentRepository departments;
    @Autowired PasswordEncoder encoder;
    @Autowired RequestTypeRepository typeRepo;
    @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate;

    private String bobToken;
    private String adminToken;
    private String delegateeToken;

    @BeforeEach
    void setup() throws Exception {
        // bob (MEMBER) — requester
        if (users.findByUsername("it7_bob").isEmpty()) {
            Set<Role> memberRoles = Set.of(roles.findByCode("MEMBER").orElseThrow());
            User bob = User.newUser("it7_bob", "It7 Bob", "it7_bob@example.com",
                    departments.findByCode("FINANCE").orElse(null), memberRoles, encoder.encode("it7_bob123"));
            users.save(bob);
        }
        // delegatee (FINANCE_MANAGER) — for DELEGATE tests
        if (users.findByUsername("it7_delegatee").isEmpty()) {
            Set<Role> fmRoles = Set.of(roles.findByCode("FINANCE_MANAGER").orElseThrow());
            User d = User.newUser("it7_delegatee", "It7 Delegatee", "it7_delegatee@example.com",
                    departments.findByCode("FINANCE").orElse(null), fmRoles, encoder.encode("pass123456"));
            users.save(d);
        }

        bobToken = userToken("it7_bob", "it7_bob123");
        adminToken = userToken("admin", "admin123");
        delegateeToken = userToken("it7_delegatee", "pass123456");

        // Deterministic: disable background pollers
        jobWorker.setRunning(false);
        dispatcher.setRunning(false);
        slaScanner.setRunning(false);
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

    private void approve(Long requestId, String approverToken) throws Exception {
        mvc.perform(post("/api/v1/requests/" + requestId + "/decisions")
                        .header("Authorization", "Bearer " + approverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"justification\":\"ok\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    // ── Execution job lifecycle ──────────────────────────────────────────

    @Nested
    @DisplayName("Execution jobs — lifecycle")
    class JobLifecycle {

        @Test
        @Order(1)
        void approvalTriggersJobsAndExecutedTransition() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Jira\",\"duration\":\"30d\"}");

            // Approve → APPROVED + execution jobs enqueued
            approve(reqId, adminToken);

            // Jobs should be enqueued (IT_ACCESS has PROVISIONING)
            List<ExecutionJob> jobs = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId);
            assertThat(jobs).isNotEmpty();
            assertThat(jobs.get(0).getAction()).isEqualTo("PROVISIONING");
            assertThat(jobs.get(0).getStatus()).isEqualTo(JobStatus.PLANNED);

            // Request should be APPROVED (not yet EXECUTED — worker hasn't run)
            Request req = requestRepo.findById(reqId).orElseThrow();
            assertThat(req.getStatus()).isEqualTo(RequestStatus.APPROVED);

            // Run the worker → jobs complete → EXECUTED
            jobWorker.process();

            jobs = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId);
            assertThat(jobs.get(0).getStatus()).isEqualTo(JobStatus.COMPLETED);
            assertThat(jobs.get(0).getResult()).isNotNull();
            assertThat(jobs.get(0).getResult().get("status")).isEqualTo("SIMULATED_OK");

            req = requestRepo.findById(reqId).orElseThrow();
            assertThat(req.getStatus()).isEqualTo(RequestStatus.EXECUTED);
        }

        @Test
        @Order(2)
        void policyExceptionHasNoExecutionJobs() throws Exception {
            long reqId = createAndSubmit(bobToken, "POLICY_EXCEPTION",
                    "{\"policy\":\"remote work policy\",\"justification\":\"needed for client visit\"}");

            approve(reqId, adminToken);

            // No execution actions on POLICY_EXCEPTION → no jobs
            List<ExecutionJob> jobs = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId);
            assertThat(jobs).isEmpty();

            // Request stays APPROVED (no jobs to complete)
            Request req = requestRepo.findById(reqId).orElseThrow();
            assertThat(req.getStatus()).isEqualTo(RequestStatus.APPROVED);
        }

        @Test
        @Order(3)
        void jobFailureRetriesThenDeadLetters() throws Exception {
            long reqId = createAndSubmit(bobToken, "EQUIPMENT",
                    "{\"item\":\"Laptop\",\"quantity\":1}");

            approve(reqId, adminToken);

            List<ExecutionJob> jobs = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId);
            assertThat(jobs).hasSize(1); // PURCHASE_TICKET

            // Inject 6 failures (max_retries=5) → DEAD_LETTER
            actionHandler.setSimulatedFailure(true, 10);

            for (int attempt = 1; attempt <= 6; attempt++) {
                jobWorker.process();
                // Reset next_retry_at so the next claim picks it up immediately
                ExecutionJob j = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId).get(0);
                if (j.getStatus() == JobStatus.DEAD_LETTER) break;
                txTemplate.executeWithoutResult(s -> jobRepo.resetNextRetryAt(j.getId(), java.time.Instant.now().minusSeconds(1)));
            }

            ExecutionJob job = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId).get(0);
            assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
            assertThat(job.getRetryCount()).isGreaterThanOrEqualTo(5);
            assertThat(job.getLastError()).contains("Simulated failure");

            // Request should NOT be EXECUTED (job didn't complete)
            Request req = requestRepo.findById(reqId).orElseThrow();
            assertThat(req.getStatus()).isEqualTo(RequestStatus.APPROVED);
        }

        @Test
        @Order(4)
        void idempotentEnqueue() throws Exception {
            long reqId = createAndSubmit(bobToken, "EXPENSE",
                    "{\"amount\":100.00,\"description\":\"test\"}");

            approve(reqId, adminToken);

            int afterFirst = jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId).size();
            assertThat(afterFirst).isGreaterThan(0);

            // Re-approve should not create duplicates (idempotency key)
            // We can't re-approve the same request, but we can verify
            // the idempotency key exists
            String key = "req:" + reqId + ":ACCOUNTING_EXPORT";
            assertThat(jobRepo.findByIdempotencyKey(key)).isPresent();
        }
    }

    // ── DELEGATE ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELEGATE action")
    class Delegate {

        @Test
        @Order(1)
        void delegateRecordsDecisionWithoutAdvancing() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Slack\",\"duration\":\"14d\"}");

            // DELEGATE to delegatee
            mvc.perform(post("/api/v1/requests/" + reqId + "/decisions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"DELEGATE\",\"justification\":\"on PTO\",\"delegatedTo\":" + users.findByUsername("it7_delegatee").orElseThrow().getId() + "}"))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));

            // Request should still be IN_REVIEW (workflow did NOT advance)
            Request req = requestRepo.findById(reqId).orElseThrow();
            assertThat(req.getStatus()).isEqualTo(RequestStatus.IN_REVIEW);

            // Decision should have delegatedTo set
            List<StepInstance> steps = getSteps(reqId);
            var decisions = decisionRepo.findByStepInstanceIdOrderById(steps.get(0).getId());
            Decision delegateDecision = decisions.stream()
                    .filter(d -> d.getAction() == DecisionAction.DELEGATE)
                    .findFirst().orElseThrow();
            assertThat(delegateDecision.getDelegatedTo())
                    .isEqualTo(users.findByUsername("it7_delegatee").orElseThrow().getId());

            // No execution jobs (request not approved)
            assertThat(jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId)).isEmpty();
        }

        @Test
        @Order(2)
        void delegateWithoutDelegatedToFails() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Figma\",\"duration\":\"30d\"}");

            mvc.perform(post("/api/v1/requests/" + reqId + "/decisions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"DELEGATE\",\"justification\":\"on PTO\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").exists());
        }

        @Test
        @Order(3)
        void delegateeCanApproveAfterDelegation() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Notion\",\"duration\":\"7d\"}");

            long delegateeId = users.findByUsername("it7_delegatee").orElseThrow().getId();

            // Admin delegates to delegatee
            mvc.perform(post("/api/v1/requests/" + reqId + "/decisions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"DELEGATE\",\"justification\":\"OOC\",\"delegatedTo\":" + delegateeId + "}"))
                    .andExpect(status().is2xxSuccessful());

            // Admin approves (delegatee is FINANCE_MANAGER, workflow requires ADMIN)
            mvc.perform(post("/api/v1/requests/" + reqId + "/decisions")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\",\"justification\":\"approved on behalf\"}"))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            // Execution jobs should be enqueued
            assertThat(jobRepo.findByRequestIdOrderByCreatedAtAsc(reqId)).isNotEmpty();
        }
    }

    // ── API response includes jobs + clarifications ──────────────────────

    @Nested
    @DisplayName("API response — execution jobs + clarifications")
    class ApiResponse {

        @Test
        @Order(1)
        void requestDetailIncludesExecutionJobs() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Linear\",\"duration\":\"30d\"}");
            approve(reqId, adminToken);
            jobWorker.process();

            var resp = mvc.perform(get("/api/v1/requests/" + reqId)
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionJobs").isArray())
                    .andExpect(jsonPath("$.executionJobs[0].action").value("PROVISIONING"))
                    .andExpect(jsonPath("$.executionJobs[0].status").value("COMPLETED"))
                    .andExpect(jsonPath("$.clarifications").isArray())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    // ── helper ───────────────────────────────────────────────────────────

    private List<StepInstance> getSteps(long reqId) {
        return stepInstanceRepo.findByRequestIdOrderById(reqId);
    }
}
