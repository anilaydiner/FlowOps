package com.flowops;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Iteration 4 — Submit + Workflow Engine v1.
 *
 * Seeded data (V4):
 *  - 4 workflows (WF_EXPENSE, WF_IT_ACCESS, WF_EQUIPMENT, WF_POLICY_EXCEPTION),
 *    each linked to its request type (WF_ + type code).
 *  - WF_EXPENSE has 2 steps: MANAGER_APPROVAL (always), then FINANCE_REVIEW
 *    (condition amount > 5000). All steps require role ADMIN.
 *
 * Seeded users: admin (ADMIN), alice (MEMBER).
 * All workflow steps require ADMIN, so alice (MEMBER) can never approve —
 * which conveniently isolates the SoD and role-eligibility checks.
 */
@AutoConfigureMockMvc
class WorkflowApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    // ---------- helpers ----------

    private String token(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthDto(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String adminToken() throws Exception {
        return token("admin", "admin123");
    }

    private String aliceToken() throws Exception {
        return token("alice", "alice123");
    }

    /** Create a member user with the given roles (admin only). Returns the token. */
    private String createUser(String username, String password, String... roles) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < roles.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(roles[i]).append('"');
        }
        sb.append(']');
        mvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","displayName":"%s","password":"%s","roles":%s}
                                """.formatted(username, username, password, sb)))
                .andExpect(status().isCreated());
        return token(username, password);
    }

    /** Create a draft request as the caller; returns the id. */
    private long createDraft(String token, String typeCode, String payloadJson) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeCode":"%s","payload":%s}
                                """.formatted(typeCode, payloadJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    }

    // ---------- seeded workflows ----------

    @Nested
    @DisplayName("Workflows — seeded catalog")
    class SeededWorkflows {

        @Test
        void allFourWorkflowsSeeded() throws Exception {
            // Note: other tests may create extra workflows in the shared DB, so we
            // assert the four seeded codes are present rather than an exact total.
            mvc.perform(get("/api/v1/workflows")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'WF_EXPENSE')]").exists())
                    .andExpect(jsonPath("$.content[?(@.code == 'WF_IT_ACCESS')]").exists())
                    .andExpect(jsonPath("$.content[?(@.code == 'WF_EQUIPMENT')]").exists())
                    .andExpect(jsonPath("$.content[?(@.code == 'WF_POLICY_EXCEPTION')]").exists());
        }

        @Test
        void expenseWorkflowHasConditionalFinanceStep() throws Exception {
            MvcResult res = mvc.perform(get("/api/v1/workflows")
                            .param("code", "WF_EXPENSE")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].steps.length()").value(2))
                    .andReturn();
            JsonNode steps = mapper.readTree(res.getResponse().getContentAsString())
                    .get("content").get(0).get("steps");
            // step 1: manager approval, no condition
            assertThatJson(steps.get(0).get("code").asText(), "MANAGER_APPROVAL");
            // step 2: finance review, conditional on amount > 5000
            JsonNode finance = steps.get(1);
            assertThatJson(finance.get("code").asText(), "FINANCE_REVIEW");
            assertThatJson(finance.get("condition").get("field").asText(), "amount");
            assertThatJson(finance.get("condition").get("op").asText(), ">");
            assertThatJson(finance.get("condition").get("value").asInt(), 5000);
        }

        @Test
        void filterByName() throws Exception {
            mvc.perform(get("/api/v1/workflows")
                            .param("name", "IT Access")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].code").value("WF_IT_ACCESS"));
        }
    }

    @Nested
    @DisplayName("Workflows — admin CRUD")
    class WorkflowCrud {

        @Test
        void admin_canCreateWorkflow() throws Exception {
            mvc.perform(post("/api/v1/workflows")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "code": "WF_VACATION",
                                      "name": "Vacation Approval",
                                      "steps": [
                                        {"position":1,"code":"LEAD_APPROVAL","name":"Lead Approval",
                                         "requiredRole":"ADMIN","completionRule":"ANY_OF","requiredApprovals":1}
                                      ]
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("WF_VACATION"))
                    .andExpect(jsonPath("$.steps.length()").value(1))
                    .andExpect(jsonPath("$.steps[0].code").value("LEAD_APPROVAL"))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        void member_cannotCreateWorkflow_returns403() throws Exception {
            mvc.perform(post("/api/v1/workflows")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"WF_HACK","name":"Hacker","steps":[
                                       {"code":"S","name":"S","requiredRole":"ADMIN"}]}
                                    """))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------- submit lifecycle ----------

    @Nested
    @DisplayName("Requests — submit + workflow")
    class Submit {

        @Test
        void alice_submitsLowExpense_managerStepPending() throws Exception {
            long id = createDraft(aliceToken(), "EXPENSE",
                    "{\"amount\":250.75,\"description\":\"Client dinner\"}");

            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                    .andExpect(jsonPath("$.submittedAt").isNotEmpty())
                    .andExpect(jsonPath("$.steps.length()").value(1))
                    .andExpect(jsonPath("$.steps[0].code").value("MANAGER_APPROVAL"))
                    .andExpect(jsonPath("$.steps[0].state").value("PENDING"))
                    .andExpect(jsonPath("$.steps[0].requiredRole").value("ADMIN"));
        }

        @Test
        void submit_nonDraft_returns409() throws Exception {
            long id = createDraft(aliceToken(), "EXPENSE", "{\"amount\":10,\"description\":\"x\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));
            // second submit → 409 (no longer DRAFT)
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isConflict());
        }

        @Test
        void submit_nonOwner_returns404() throws Exception {
            long id = createDraft(aliceToken(), "EXPENSE", "{\"amount\":10,\"description\":\"x\"}");
            String carol = createUser("carol4", "carolpass123", "MEMBER");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + carol))
                    .andExpect(status().isNotFound());
        }
    }

    // ---------- conditional step + completion ----------

    @Nested
    @DisplayName("Requests — conditional steps + approval flow")
    class ApprovalFlow {

        @Test
        void lowAmount_financeReviewSkipped_thenApproved() throws Exception {
            long id = createDraft(aliceToken(), "EXPENSE",
                    "{\"amount\":250,\"description\":\"Coffee run\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));

            // admin (ADMIN) approves MANAGER_APPROVAL
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\",\"justification\":\"Looks fine\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    // FINANCE_REVIEW (amount>5000) is SKIPPED as Not Applicable
                    .andExpect(jsonPath("$.steps[?(@.code == 'FINANCE_REVIEW')].state").value("SKIPPED"))
                    .andExpect(jsonPath("$.steps[?(@.code == 'MANAGER_APPROVAL')].state").value("SATISFIED"))
                    .andExpect(jsonPath("$.steps[?(@.code == 'MANAGER_APPROVAL')].decisions[0].action").value("APPROVE"));
        }

        @Test
        void highAmount_financeReviewRequired_twoStepFlow() throws Exception {
            long id = createDraft(aliceToken(), "EXPENSE",
                    "{\"amount\":6000,\"description\":\"Server upgrade\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));

            // approve step 1 (MANAGER_APPROVAL) → FINANCE_REVIEW now PENDING, still IN_REVIEW
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                    .andExpect(jsonPath("$.steps[?(@.code == 'FINANCE_REVIEW')].state").value("PENDING"));

            // approve step 2 (FINANCE_REVIEW) → APPROVED
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\",\"justification\":\"Budget OK\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }

        @Test
        void admin_rejectTerminatesWorkflow() throws Exception {
            long id = createDraft(aliceToken(), "IT_ACCESS",
                    "{\"resource\":\"GitHub\",\"duration\":\"30d\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));

            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"REJECT\",\"justification\":\"Not justified\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"))
                    .andExpect(jsonPath("$.steps[?(@.code == 'IT_APPROVAL')].decisions[0].action").value("REJECT"));
        }

        @Test
        void decide_onCompletedRequest_returns409() throws Exception {
            long id = createDraft(aliceToken(), "EQUIPMENT",
                    "{\"item\":\"Monitor\",\"quantity\":1}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk());
            // approve → APPROVED (single-step workflow)
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
            // decide again → 409 (no longer IN_REVIEW)
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isConflict());
        }
    }

    // ---------- SoD + role eligibility ----------

    @Nested
    @DisplayName("Requests — separation of duties + role eligibility")
    class SoD {

        @Test
        void requester_cannotDecideOwn_returns403() throws Exception {
            long id = createDraft(aliceToken(), "EXPENSE", "{\"amount\":10,\"description\":\"x\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk());
            // alice (requester) tries to approve her own request → SoD 403
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void member_withoutRole_cannotDecide_returns403() throws Exception {
            long id = createDraft(aliceToken(), "EXPENSE", "{\"amount\":10,\"description\":\"x\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk());
            // carol is not the requester (SoD passes) but lacks the ADMIN role → 403
            String carol = createUser("carol5", "carolpass123", "MEMBER");
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + carol)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void secondAdmin_canApprove_otherAdminsRequest() throws Exception {
            // bob (ADMIN) is the requester; admin (ADMIN) is the approver — SoD passes
            String bob = createUser("bob6", "bobpass123", "ADMIN");
            long id = createDraft(bob, "EXPENSE", "{\"amount\":10,\"description\":\"x\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + bob))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isOk());
        }
    }

    // ---------- blocked step (no eligible approver) ----------

    @Nested
    @DisplayName("Requests — blocked step (no eligible approver)")
    class Blocked {

        @Test
        void stepBlocked_whenNoUserHoldsRequiredRole() throws Exception {
            // A workflow whose step requires a role that NO user has. The
            // eligibility probe (countActiveByRoleCode) therefore returns 0 and
            // the materialized step is BLOCKED — independent of which admins
            // other tests happen to create.
            String admin = adminToken();
            mvc.perform(post("/api/v1/workflows")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "code": "WF_PHANTOM",
                                      "name": "Phantom Approval",
                                      "steps": [
                                        {"position":1,"code":"PHANTOM_STEP","name":"Phantom Step",
                                         "requiredRole":"PHANTOM_ROLE","completionRule":"ANY_OF","requiredApprovals":1}
                                      ]
                                    }
                                    """))
                    .andExpect(status().isCreated());
            mvc.perform(post("/api/v1/request-types")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"PHANTOM_REQ","name":"Phantom","departmentCode":"FINANCE",
                                     "workflowCode":"WF_PHANTOM","requiredFields":{"note":"Why"}}
                                    """))
                    .andExpect(status().isCreated());

            long id = createDraft(aliceToken(), "PHANTOM_REQ", "{\"note\":\"no one can approve\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                    .andExpect(jsonPath("$.steps[0].code").value("PHANTOM_STEP"))
                    .andExpect(jsonPath("$.steps[0].state").value("BLOCKED"))
                    .andExpect(jsonPath("$.steps[0].blockedReason").isNotEmpty());
        }
    }

    // ---------- ALL_OF / K_OF_N completion rule ----------

    @Nested
    @DisplayName("Requests — multi-approval completion rule")
    class MultiApproval {

        /** Admin creates a workflow whose single step requires 2 approvals (ALL_OF),
         *  plus a request type bound to it. Returns the new type code. */
        private String setupDualApprovalType(String suffix) throws Exception {
            mvc.perform(post("/api/v1/workflows")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "code":"WF_DUAL_%s",
                                      "name":"Dual Approval",
                                      "steps":[
                                        {"position":1,"code":"DUAL_APPROVAL","name":"Dual Approval",
                                         "requiredRole":"ADMIN","completionRule":"ALL_OF","requiredApprovals":2}
                                      ]
                                    }
                                    """.formatted(suffix)))
                    .andExpect(status().isCreated());
            mvc.perform(post("/api/v1/request-types")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"DUAL_%s","name":"Dual","departmentCode":"FINANCE",
                                     "workflowCode":"WF_DUAL_%s","requiredFields":{"note":"Why"}}
                                    """.formatted(suffix, suffix)))
                    .andExpect(status().isCreated());
            return "DUAL_" + suffix;
        }

        @Test
        void twoApprovalsRequired_beforeAdvance() throws Exception {
            String typeCode = setupDualApprovalType("A");
            String bob = createUser("bob7", "bobpass123", "ADMIN");
            long id = createDraft(aliceToken(), typeCode, "{\"note\":\"needs two\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.steps[0].state").value("PENDING"))
                    .andExpect(jsonPath("$.steps[0].requiredApprovals").value(2));

            // first approval (bob) → still IN_REVIEW (1 < 2)
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + bob)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));

            // second approval (admin) → satisfied → APPROVED
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.steps[0].state").value("SATISFIED"))
                    .andExpect(jsonPath("$.steps[0].decisions.length()").value(2));
        }
    }

    // ---------- close ----------

    @Nested
    @DisplayName("Requests — close (EXECUTED|REJECTED → CLOSED)")
    class Close {

        @Test
        void rejectedRequest_canBeClosed_byRequester() throws Exception {
            long id = createDraft(aliceToken(), "IT_ACCESS",
                    "{\"resource\":\"Jira\",\"duration\":\"7d\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"REJECT\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));

            // requester closes
            mvc.perform(post("/api/v1/requests/" + id + "/close")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"));

            // close again → 409 (terminal)
            mvc.perform(post("/api/v1/requests/" + id + "/close")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isConflict());
        }

        @Test
        void draftRequest_cannotBeClosed_returns409() throws Exception {
            long id = createDraft(aliceToken(), "EQUIPMENT",
                    "{\"item\":\"Chair\",\"quantity\":1}");
            mvc.perform(post("/api/v1/requests/" + id + "/close")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        void closedByAdmin_onRejected() throws Exception {
            long id = createDraft(aliceToken(), "POLICY_EXCEPTION",
                    "{\"policy\":\"Overtime\",\"justification\":\"deadline\"}");
            mvc.perform(post("/api/v1/requests/" + id + "/submit")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"REJECT\"}"))
                    .andExpect(status().isOk());
            // admin (not the requester) closes
            mvc.perform(post("/api/v1/requests/" + id + "/close")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"));
        }
    }

    private static void assertThatJson(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertThatJson(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    record AuthDto(String username, String password) {
    }
}
