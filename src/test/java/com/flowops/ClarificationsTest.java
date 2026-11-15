package com.flowops;

import com.flowops.domain.*;
import com.flowops.infra.persistence.*;
import com.flowops.infra.worker.OutboxDispatcher;
import com.flowops.infra.worker.SlaScanner;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Iteration 7 tests: Clarifications API (PLAN §8).
 *
 * Verifies:
 * - POST /api/v1/requests/{id}/clarifications → 201
 * - GET  /api/v1/requests/{id}/clarifications → list
 * - PUT  /api/v1/requests/{id}/clarifications/{cid} → answer
 * - Permission: only owner/admin
 * - Terminal request → 409
 * - Already answered → 409
 */
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClarificationsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ClarificationJpaRepository clariRepo;
    @Autowired RequestJpaRepository requestRepo;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired SlaScanner slaScanner;
    @Autowired com.flowops.infra.worker.JobWorker jobWorker;
    @Autowired UserJpaRepository users;
    @Autowired RoleJpaRepository roles;
    @Autowired DepartmentJpaRepository departments;
    @Autowired PasswordEncoder encoder;

    private String bobToken;
    private String adminToken;
    private String carolToken;

    @BeforeEach
    void setup() throws Exception {
        if (users.findByUsername("it7_bob").isEmpty()) {
            Set<Role> memberRoles = Set.of(roles.findByCode("MEMBER").orElseThrow());
            User bob = User.newUser("it7_bob", "It7 Bob", "it7_bob@example.com",
                    departments.findByCode("FINANCE").orElse(null), memberRoles, encoder.encode("it7_bob123"));
            users.save(bob);
        }
        if (users.findByUsername("it7_carol").isEmpty()) {
            Set<Role> memberRoles = Set.of(roles.findByCode("MEMBER").orElseThrow());
            User carol = User.newUser("it7_carol", "It7 Carol", "it7_carol@example.com",
                    departments.findByCode("IT").orElse(null), memberRoles, encoder.encode("it7_carol123"));
            users.save(carol);
        }

        bobToken = userToken("it7_bob", "it7_bob123");
        adminToken = userToken("admin", "admin123");
        carolToken = userToken("it7_carol", "it7_carol123");

        dispatcher.setRunning(false);
        slaScanner.setRunning(false);
        jobWorker.setRunning(false);
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

    private long createDraft(String token, String typeCode, String payload) throws Exception {
        var resp = mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeCode\":\"" + typeCode + "\",\"payload\":" + payload + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int idIdx = resp.indexOf("\"id\":");
        return Long.parseLong(resp.substring(idIdx + 5).split("[,}\\s]")[0].trim());
    }

    private long createAndSubmit(String token, String typeCode, String payload) throws Exception {
        long id = createDraft(token, typeCode, payload);
        mvc.perform(post("/api/v1/requests/" + id + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is2xxSuccessful());
        return id;
    }

    // ── Clarification CRUD ───────────────────────────────────────────────

    @Nested
    @DisplayName("Clarifications — ask, list, answer")
    class ClarificationCrud {

        @Test
        @Order(1)
        void askAndList() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Jira\",\"duration\":\"30d\"}");

            // Ask
            mvc.perform(post("/api/v1/requests/" + reqId + "/clarifications")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"What is the expected team size?\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.question").value("What is the expected team size?"));

            // List
            mvc.perform(get("/api/v1/requests/" + reqId + "/clarifications")
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].question").value("What is the expected team size?"))
                    .andExpect(jsonPath("$[0].answer").doesNotExist());
        }

        @Test
        @Order(2)
        void answerClarification() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Slack\",\"duration\":\"14d\"}");

            // Ask
            var askResp = mvc.perform(post("/api/v1/requests/" + reqId + "/clarifications")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"Which workspace?\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            int cidIdx = askResp.indexOf("\"id\":");
            long cid = Long.parseLong(askResp.substring(cidIdx + 5).split("[,}\\s]")[0].trim());

            // Answer
            mvc.perform(put("/api/v1/requests/" + reqId + "/clarifications/" + cid)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answer\":\"Engineering workspace\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").value("Engineering workspace"))
                    .andExpect(jsonPath("$.answeredBy").isNumber());

            // Already answered → 409
            mvc.perform(put("/api/v1/requests/" + reqId + "/clarifications/" + cid)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answer\":\"Second answer\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @Order(3)
        void nonOwnerCannotAccess() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Figma\",\"duration\":\"30d\"}");

            // Carol (not owner, not admin) → 404
            mvc.perform(get("/api/v1/requests/" + reqId + "/clarifications")
                            .header("Authorization", "Bearer " + carolToken))
                    .andExpect(status().isNotFound());

            mvc.perform(post("/api/v1/requests/" + reqId + "/clarifications")
                            .header("Authorization", "Bearer " + carolToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"Hi?\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @Order(4)
        void terminalRequestRejectsClarifications() throws Exception {
            long reqId = createDraft(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Linear\",\"duration\":\"30d\"}");

            // Cancel → CANCELLED (terminal)
            mvc.perform(post("/api/v1/requests/" + reqId + "/cancel")
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk());

            mvc.perform(post("/api/v1/requests/" + reqId + "/clarifications")
                            .header("Authorization", "Bearer " + bobToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"Too late?\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @Order(5)
        void clarificationAppearsInRequestDetail() throws Exception {
            long reqId = createAndSubmit(bobToken, "IT_ACCESS",
                    "{\"resource\":\"Notion\",\"duration\":\"7d\"}");

            mvc.perform(post("/api/v1/requests/" + reqId + "/clarifications")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"Which plan?\"}"))
                    .andExpect(status().isCreated());

            mvc.perform(get("/api/v1/requests/" + reqId)
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.clarifications[0].question").value("Which plan?"));
        }
    }
}
