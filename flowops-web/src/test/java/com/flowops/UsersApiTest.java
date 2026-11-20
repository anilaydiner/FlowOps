package com.flowops;

import com.flowops.application.notification.outbox.OutboxMessage;
import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.application.notification.port.EmailLogEntryRepository;
import com.flowops.mail.SimulatedEmailGateway;
import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class UsersApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    // ---------- helpers ----------

    private String token(String username, String password) throws Exception {
        MvcResult result = mvc
            .perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new AuthDto(username, password))))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private String adminToken() throws Exception {
        return token("admin", "admin123");
    }

    private String aliceToken() throws Exception {
        return token("alice", "alice123");
    }

    private String createUser(String admin, String username, String department) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("username", username, "displayName",
                username + " Display", "departmentCode", department, "password", "carolpass123"));
        MvcResult result = mvc
            .perform(post("/api/v1/users").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return result.getResponse().getContentAsString();
    }

    // ---------- auth ----------

    @Nested
    @DisplayName("POST /api/v1/auth/token")
    class Auth {

        @Test
        void validCredentials_returnBearerToken() throws Exception {
            String t = adminToken();
            assertThat(t).isNotBlank();
        }

        @Test
        void wrongPassword_returns401ProblemDetails() throws Exception {
            mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new AuthDto("admin", "nope"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").isString())
                .andExpect(jsonPath("$.correlationId").isString());
        }

        @Test
        void unknownUser_returns401() throws Exception {
            mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new AuthDto("ghost", "x12345678"))))
                .andExpect(status().isUnauthorized());
        }

    }

    // ---------- users ----------

    @Nested
    @DisplayName("Users API")
    class Users {

        @Test
        void admin_canCreateUser() throws Exception {
            MvcResult result = mvc
                .perform(post("/api/v1/users").header("Authorization", "Bearer " + adminToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "username": "bob",
                              "displayName": "Bob Brown",
                              "email": "bob@example.com",
                              "departmentCode": "IT",
                              "roles": ["MEMBER"],
                              "password": "bobpass123"
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles[?(@ == 'MEMBER')]").exists())
                .andExpect(jsonPath("$.departmentCode").value("IT"))
                .andReturn();
            JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("password")).as("password must never be exposed").isNull();
        }

        @Test
        void admin_canCreateUserWithMultipleRoles() throws Exception {
            mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "username": "dual",
                          "displayName": "Dual Role",
                          "roles": ["ADMIN", "MEMBER"],
                          "password": "dualpass123"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.roles[?(@ == 'ADMIN')]").exists())
                .andExpect(jsonPath("$.roles[?(@ == 'MEMBER')]").exists());
        }

        @Test
        void duplicateUsername_returns409() throws Exception {
            mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "alice", "displayName": "Impostor", "password": "x12345678"}
                        """)).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        }

        @Test
        void member_cannotCreateUser_returns403ProblemDetails() throws Exception {
            mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + aliceToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "mallory", "displayName": "M", "password": "x12345678"}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.correlationId").isString());
        }

        @Test
        void unauthenticated_create_returns401() throws Exception {
            mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content("""
                    {"username": "ghost", "displayName": "G", "password": "x12345678"}
                    """)).andExpect(status().isUnauthorized());
        }

        @Test
        void invalidPayload_returns400WithViolations() throws Exception {
            mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "X!", "displayName": "", "password": "short"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.violations.username").isString())
                .andExpect(jsonPath("$.violations.password").isString());
        }

        @Test
        void me_returnsOwnProfile() throws Exception {
            mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.departmentCode").value("FINANCE"))
                .andExpect(jsonPath("$.roles").isArray());
        }

        @Test
        void admin_listsAllUsers() throws Exception {
            mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.content[?(@.username == 'admin')]").exists())
                .andExpect(jsonPath("$.content[?(@.username == 'alice')]").exists());
        }

        @Test
        void list_filterByRoleCode() throws Exception {
            mvc.perform(get("/api/v1/users?roleCode=ADMIN").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.username == 'admin')]").exists())
                .andExpect(jsonPath("$.content[0].roles[?(@ == 'ADMIN')]").exists());
        }

        @Test
        void list_filterByDepartmentCode() throws Exception {
            mvc.perform(get("/api/v1/users?departmentCode=FINANCE").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.username == 'alice')]").exists())
                .andExpect(jsonPath("$.content[0].departmentCode").value("FINANCE"));
        }

        @Test
        void list_filterByUsername_partialMatch() throws Exception {
            mvc.perform(get("/api/v1/users?username=ali").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("alice"));
        }

        @Test
        void list_combinedFilters_and() throws Exception {
            // roleCode=ADMIN AND departmentCode=FINANCE → no user matches
            mvc.perform(get("/api/v1/users?roleCode=ADMIN&departmentCode=FINANCE").header("Authorization",
                    "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty());
        }

        @Test
        void list_sortDescending_byUsername() throws Exception {
            mvc.perform(get("/api/v1/users?sort=username,desc").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].username").isString())
                .andExpect(jsonPath("$.content[?(@.username == 'admin')]").exists());
        }

        @Test
        void list_multiSort_createdAtDesc_thenUsernameAsc() throws Exception {
            mvc.perform(get("/api/v1/users?sort=createdAt,desc&sort=username,asc").header("Authorization",
                    "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].username").isString());
        }

        @Test
        void list_sortWhitelist_rejectsUnknownField() throws Exception {
            // "passwordHash" is NOT in the whitelist → silently ignored, falls back to id
            mvc.perform(get("/api/v1/users?sort=passwordHash,desc").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").isNumber());
        }

        @Test
        void list_pagination_sizeAndPage() throws Exception {
            mvc.perform(get("/api/v1/users?page=1&size=1").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.first").value(true));

            mvc.perform(get("/api/v1/users?page=2&size=1").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.first").value(false));
        }

        @Test
        void member_cannotSeeOtherUser_returns403() throws Exception {
            mvc.perform(get("/api/v1/users/1").header("Authorization", "Bearer " + aliceToken()))
                .andExpect(status().isForbidden());
        }

        @Test
        void admin_canUpdateUser() throws Exception {
            String createBody = createUser(adminToken(), "carol", "HR");
            long id = mapper.readTree(createBody).get("id").asLong();
            mvc.perform(put("/api/v1/users/" + id).header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName": "Carol Danvers"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("carol"))
                .andExpect(jsonPath("$.displayName").value("Carol Danvers"));
        }

        @Test
        void admin_canUpdateUserRoles() throws Exception {
            String createBody = createUser(adminToken(), "erin", "IT");
            long id = mapper.readTree(createBody).get("id").asLong();
            mvc.perform(put("/api/v1/users/" + id).header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roles": ["ADMIN"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles[?(@ == 'ADMIN')]").exists());
        }

        @Test
        void admin_canDeleteUser() throws Exception {
            String createBody = createUser(adminToken(), "dave", "OPERATIONS");
            long id = mapper.readTree(createBody).get("id").asLong();
            mvc.perform(delete("/api/v1/users/" + id).header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/users/" + id).header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound());
        }

        @Test
        void admin_cannotDeleteSelf_returns409() throws Exception {
            mvc.perform(delete("/api/v1/users/1").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isConflict());
        }

        @Autowired
        private OutboxMessageRepository outboxRepo;

        @Autowired
        private OutboxDispatcher dispatcher;

        @Autowired
        private EmailLogEntryRepository emailLogRepo;

        @Autowired
        private JobWorker jobWorker;

        @Autowired
        private SlaScanner slaScanner;

        @Autowired
        private SimulatedEmailGateway emailGateway;

        /**
         * Make the outbox deterministic for this class: pause the background schedulers
         * (so the test's explicit {@code dispatch()} is the only delivery path — no
         * {@code FOR UPDATE SKIP LOCKED} races) and clear any failure injected by a prior
         * test class (the gateway bean is shared across the whole suite).
         */
        @BeforeEach
        void deterministicOutbox() {
            jobWorker.setRunning(false);
            dispatcher.setRunning(false);
            slaScanner.setRunning(false);
            emailGateway.clearSimulatedFailure();
        }

        @Test
        void creatingUser_sendsWelcomeMail_withInitialPassword() throws Exception {
            String password = "welcome123";
            String email = "welcometest1@example.com";
            MvcResult result = mvc
                .perform(post("/api/v1/users").header("Authorization", "Bearer " + adminToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(java.util.Map.of("username", "welcometest1", "displayName",
                            "Welcome Test", "email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
            long id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

            // 1) Welcome mail enqueued atomically with user creation (idempotent —
            // exactly one)
            java.util.List<OutboxMessage> welcomes = outboxRepo.findAll()
                .stream()
                .filter(m -> m.getRecipientId() != null && m.getRecipientId() == id)
                .filter(m -> m.getIdempotencyKey().endsWith(":welcome"))
                .toList();
            assertThat(welcomes).hasSize(1);
            assertThat(welcomes.get(0).getChannel()).isEqualTo("EMAIL");
            assertThat(welcomes.get(0).getPayload()).contains(password);

            // 2) Dispatch until delivered. dispatch() claims the OLDEST 50 due
            // rows, and a pending backlog from other test classes can push
            // this (newest) row out of a single batch — so loop until the
            // welcome mail is actually in the email log.
            for (int i = 0; i < 50 && !deliveredTo(email); i++) {
                dispatcher.dispatch();
            }
            assertThat(deliveredTo(email)).as("welcome email delivered to %s", email).isTrue();

            // 3) The user can actually sign in with the delivered password
            assertThat(token("welcometest1", password)).isNotBlank();
        }

        private boolean deliveredTo(String email) {
            return emailLogRepo.findAll().stream().anyMatch(e -> email.equals(e.getToAddress()));
        }

    }

    // ---------- roles ----------

    @Nested
    @DisplayName("Roles API")
    class Roles {

        @Test
        void list_roles() throws Exception {
            mvc.perform(get("/api/v1/roles").header("Authorization", "Bearer " + aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.content[?(@.code == 'ADMIN')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == 'MEMBER')]").exists());
        }

        @Test
        void list_roles_filteredByCode() throws Exception {
            mvc.perform(get("/api/v1/roles?code=ADM").header("Authorization", "Bearer " + aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("ADMIN"));
        }

        @Test
        void get_role_byId() throws Exception {
            mvc.perform(get("/api/v1/roles/1").header("Authorization", "Bearer " + aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.permissions").isArray());
        }

        @Test
        void admin_canCreateRole() throws Exception {
            mvc.perform(post("/api/v1/roles").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "code": "SCRATCH_ROLE",
                          "name": "Scratch Role",
                          "description": "Temporary test role",
                          "permissions": ["users:read"]
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SCRATCH_ROLE"))
                .andExpect(jsonPath("$.permissions[?(@ == 'users:read')]").exists());
        }

        @Test
        void admin_canUpdateRole() throws Exception {
            // Update a SCRATCH role — not a built-in one. Role update replaces
            // the permission set, and this test suite shares one database:
            // clobbering the ADMIN role's permissions here would 403 every
            // approve/reject in test classes that run after this one.
            String createBody = mvc
                .perform(post("/api/v1/roles").header("Authorization", "Bearer " + adminToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"code": "UPDTEST", "name": "Update Target",
                             "permissions": ["users:read"]}
                            """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
            long roleId = mapper.readTree(createBody).get("id").asLong();

            mvc.perform(put("/api/v1/roles/" + roleId).header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "System Administrator",
                         "permissions": ["users:read", "users:write"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("System Administrator"))
                .andExpect(jsonPath("$.permissions.length()").value(2));
        }

        @Test
        void admin_cannotDeleteBuiltInRole() throws Exception {
            mvc.perform(delete("/api/v1/roles/1").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        void member_cannotCreateRole_returns403() throws Exception {
            mvc.perform(post("/api/v1/roles").header("Authorization", "Bearer " + aliceToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "HACKER", "name": "Hacker"}
                        """)).andExpect(status().isForbidden());
        }

    }

    // ---------- permissions ----------

    @Nested
    @DisplayName("Permissions API")
    class Permissions {

        @Test
        void list_permissions() throws Exception {
            mvc.perform(get("/api/v1/permissions").header("Authorization", "Bearer " + aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").isNumber())
                .andExpect(jsonPath("$.content[?(@.code == 'users:read')]").exists());
        }

        @Test
        void list_permissions_filteredByCode() throws Exception {
            mvc.perform(get("/api/v1/permissions?code=users:").header("Authorization", "Bearer " + aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(8))
                .andExpect(jsonPath("$.content[0].code").isString())
                .andExpect(jsonPath("$.content[0].code").value(org.hamcrest.Matchers.startsWith("users")));
        }

    }

    // ---------- reference data ----------

    @Test
    void departmentsAreSeededAndReadable() throws Exception {
        mvc.perform(get("/api/v1/departments").header("Authorization", "Bearer " + aliceToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].code").isString())
            .andExpect(jsonPath("$.content[?(@.code == 'FINANCE')].name").value("Finance"));
    }

    // ---------- correlation id ----------

    @Test
    void correlationId_generatedWhenAbsent() throws Exception {
        mvc.perform(get("/api/v1/departments").header("Authorization", "Bearer " + aliceToken()))
            .andExpect(header().exists("X-Correlation-Id"))
            .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesRegex("[0-9a-f-]{36}")));
    }

    @Test
    void correlationId_echoedWhenProvided() throws Exception {
        mvc.perform(get("/api/v1/departments").header("Authorization", "Bearer " + aliceToken())
            .header("X-Correlation-Id", "trace-abc-123"))
            .andExpect(header().string("X-Correlation-Id", "trace-abc-123"));
    }

    record AuthDto(String username, String password) {
    }

}
