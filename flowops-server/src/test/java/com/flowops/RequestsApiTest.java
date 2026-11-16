package com.flowops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RequestsApiTest extends AbstractIntegrationTest {

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

    // ---------- request types: seeded catalog ----------

    @Nested
    @DisplayName("Request Types — seeded catalog")
    class SeededTypes {

        @Test
        void allFourTypesSeeded() throws Exception {
            mvc.perform(get("/api/v1/request-types")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").isNumber())
                    .andExpect(jsonPath("$.content[?(@.code == 'EXPENSE')]").exists())
                    .andExpect(jsonPath("$.content[?(@.code == 'IT_ACCESS')]").exists())
                    .andExpect(jsonPath("$.content[?(@.code == 'EQUIPMENT')]").exists())
                    .andExpect(jsonPath("$.content[?(@.code == 'POLICY_EXCEPTION')]").exists());
        }

        @Test
        void itAccessHasRequiredDuration() throws Exception {
            mvc.perform(get("/api/v1/request-types")
                            .param("code", "IT_ACCESS")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].requiredFields.duration").isString())
                    .andExpect(jsonPath("$.content[0].validationRules.duration.pattern").isString())
                    .andExpect(jsonPath("$.content[0].departmentCode").value("IT"));
        }

        @Test
        void filterByDepartment() throws Exception {
            mvc.perform(get("/api/v1/request-types?departmentCode=FINANCE")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").isNumber())
                    .andExpect(jsonPath("$.content[?(@.code == 'EXPENSE')].departmentCode").value("FINANCE"))
                    .andExpect(jsonPath("$.content[?(@.code == 'EXPENSE')].departmentName").value("Finance"));
        }

        @Test
        void newDepartmentsSeeded() throws Exception {
            mvc.perform(get("/api/v1/departments?code=PROCUREMENT")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("Procurement"));
        }
    }

    // ---------- request types: admin CRUD ----------

    @Nested
    @DisplayName("Request Types — admin CRUD")
    class TypeCrud {

        @Test
        void admin_canCreateType() throws Exception {
            mvc.perform(post("/api/v1/request-types")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "code": "TRAVEL",
                                      "name": "Travel Request",
                                      "description": "Business travel",
                                      "departmentCode": "FINANCE",
                                      "requiredFields": {"destination": "Where to", "amount": "Budget"},
                                      "validationRules": {"amount": {"type": "number", "min": 10}}
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("TRAVEL"))
                    .andExpect(jsonPath("$.requiredFields.destination").isString())
                    .andExpect(jsonPath("$.validationRules.amount.min").isNumber());
        }

        @Test
        void admin_canUpdateType() throws Exception {
            mvc.perform(put("/api/v1/request-types/1")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Expense Reimbursement (updated)"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Expense Reimbursement (updated)"));
        }

        @Test
        void member_cannotCreateType_returns403() throws Exception {
            mvc.perform(post("/api/v1/request-types")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code": "HACK", "name": "Hacker Type"}
                                    """))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------- draft lifecycle ----------

    @Nested
    @DisplayName("Requests — draft lifecycle")
    class Drafts {

        @Test
        void alice_createsExpenseDraft() throws Exception {
            mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "typeCode": "EXPENSE",
                                      "payload": {"amount": 250.75, "description": "Client dinner"}
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.typeCode").value("EXPENSE"))
                    .andExpect(jsonPath("$.departmentCode").value("FINANCE"))
                    .andExpect(jsonPath("$.payload.amount").value(250.75))
                    .andExpect(jsonPath("$.requester.username").value("alice"))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.correlationId").isString());
        }

        @Test
        void createsItAccessDraft_withTimeBoundDuration() throws Exception {
            mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "typeCode": "IT_ACCESS",
                                      "payload": {"resource": "Jira", "duration": "30d"}
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        void missingRequiredField_returns400WithViolations() throws Exception {
            mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "typeCode": "EXPENSE",
                                      "payload": {"description": "No amount given"}
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.violations.amount").isString())
                    .andExpect(jsonPath("$.correlationId").isString());
        }

        @Test
        void itAccess_durationMustBeTimeBound() throws Exception {
            // "forever" violates the duration pattern → 400 with violations
            mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "typeCode": "IT_ACCESS",
                                      "payload": {"resource": "Jira", "duration": "forever"}
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations.duration").isString());
        }

        @Test
        void itAccess_missingDuration_returns400() throws Exception {
            mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"typeCode": "IT_ACCESS", "payload": {"resource": "Jira"}}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations.duration").isString());
        }

        @Test
        void unknownType_returns404() throws Exception {
            mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"typeCode": "NOPE", "payload": {}}
                                    """))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        void owner_canEditDraft() throws Exception {
            // create
            MvcResult created = mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"typeCode": "EQUIPMENT",
                                     "payload": {"item": "Monitor", "quantity": 1}}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn();
            long id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

            // edit
            mvc.perform(put("/api/v1/requests/" + id)
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"payload": {"item": "4K Monitor", "quantity": 2}}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.payload.quantity").value(2))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        void editDraft_reValidatesPayload() throws Exception {
            MvcResult created = mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"typeCode": "EXPENSE",
                                     "payload": {"amount": 10, "description": "Coffee"}}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn();
            long id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

            // edit to an invalid payload → 400 violations
            mvc.perform(put("/api/v1/requests/" + id)
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"payload": {"description": "Forgot amount again"}}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations.amount").isString());
        }

        @Test
        void owner_canCancelDraft() throws Exception {
            MvcResult created = mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"typeCode": "POLICY_EXCEPTION",
                                     "payload": {"policy": "Remote work", "justification": "Family emergency"}}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn();
            long id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

            mvc.perform(post("/api/v1/requests/" + id + "/cancel")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));

            // cancel again → 409 (terminal)
            mvc.perform(post("/api/v1/requests/" + id + "/cancel")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        void nonOwner_cannotSeeOrEdit_returns403() throws Exception {
            // alice creates
            MvcResult created = mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"typeCode": "EXPENSE",
                                     "payload": {"amount": 5, "description": "Alone"}}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn();
            long id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

            // admin token: create a second member via admin to act as "other user"
            String admin = adminToken();
            mvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username": "bob2", "displayName": "Bob Two", "password": "bobpass123"}
                                    """))
                    .andExpect(status().isCreated());
            String bob = token("bob2", "bobpass123");

            // bob reads alice's request → 403 (missing request:read permission)
            mvc.perform(get("/api/v1/requests/" + id)
                            .header("Authorization", "Bearer " + bob))
                    .andExpect(status().isForbidden());

            // bob edits alice's request → 403 (missing request:update permission)
            mvc.perform(put("/api/v1/requests/" + id)
                            .header("Authorization", "Bearer " + bob)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"payload": {"amount": 999, "description": "Hacked"}}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void admin_canSeeAllRequests() throws Exception {
            mvc.perform(get("/api/v1/requests")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.totalElements").isNumber())
                    .andExpect(jsonPath("$.content[?(@.requester.username == 'alice')]").exists());
        }

        @Test
        void member_seesOnlyOwnRequests() throws Exception {
            mvc.perform(get("/api/v1/requests")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[?(@.requester.username != 'alice')]").isEmpty());
        }

        @Test
        void list_filterByStatusAndType() throws Exception {
            mvc.perform(get("/api/v1/requests?status=DRAFT&typeCode=IT_ACCESS")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.typeCode != 'IT_ACCESS' || @.status != 'DRAFT')]").isEmpty());
        }

        @Test
        void list_filterMine() throws Exception {
            mvc.perform(get("/api/v1/requests?mine=true")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.requester.username != 'admin')]").isEmpty());
        }
    }

    record AuthDto(String username, String password) {
    }
}
