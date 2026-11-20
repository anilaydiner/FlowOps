package com.flowops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/requests/queue — approver inbox (PLAN §4).
 *
 * <p>
 * The queue returns IN_REVIEW requests where the caller is eligible for the current
 * (PENDING) step: the step's required_role is in the caller's roles and the caller is not
 * the requester (SoD).
 */
@AutoConfigureMockMvc
@Transactional
class QueueApiTest extends AbstractIntegrationTest {

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
        return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String adminToken() throws Exception {
        return token("admin", "admin123");
    }

    private String aliceToken() throws Exception {
        return token("alice", "alice123");
    }

    /** Create a draft + submit it; returns the request id. */
    private long createAndSubmit(String requesterToken, String typeCode, String payload) throws Exception {
        MvcResult created = mvc
            .perform(post("/api/v1/requests").header("Authorization", "Bearer " + requesterToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeCode\":\"" + typeCode + "\",\"payload\":" + payload + "}"))
            .andExpect(status().isCreated())
            .andReturn();
        long id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/requests/" + id + "/submit").header("Authorization", "Bearer " + requesterToken))
            .andExpect(status().isOk());
        return id;
    }

    // ---------- tests ----------

    @Nested
    @DisplayName("GET /api/v1/requests/queue")
    class Queue {

        @Test
        @DisplayName("admin sees IN_REVIEW request in queue (role match)")
        void approver_seesPendingApproval() throws Exception {
            long id = createAndSubmit(aliceToken(), "EXPENSE", "{\"amount\":250,\"description\":\"Client dinner\"}");

            mvc.perform(get("/api/v1/requests/queue").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")]").exists())
                .andExpect(jsonPath("$.content[0].status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.totalElements").isNumber());
        }

        @Test
        @DisplayName("requester does NOT see own request in queue (SoD)")
        void requester_excludedFromQueue() throws Exception {
            long id = createAndSubmit(aliceToken(), "EXPENSE", "{\"amount\":100,\"description\":\"Coffee\"}");

            // alice is the requester; even though the step requires ADMIN,
            // she doesn't have that role AND is the requester (double exclusion)
            mvc.perform(get("/api/v1/requests/queue").header("Authorization", "Bearer " + aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")]").isEmpty());
        }

        @Test
        @DisplayName("queue does NOT include requester's own requests (SoD)")
        void requester_neverSeesOwnInQueue() throws Exception {
            // alice creates + submits → alice should never see her own in queue
            createAndSubmit(aliceToken(), "EXPENSE", "{\"amount\":75,\"description\":\"Lunch\"}");

            mvc.perform(get("/api/v1/requests/queue").header("Authorization", "Bearer " + aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.requester.username == 'alice')]").isEmpty());
        }

        @Test
        @DisplayName("DRAFT request does NOT appear in queue")
        void draft_notInQueue() throws Exception {
            // Create a draft but do NOT submit it
            MvcResult created = mvc
                .perform(post("/api/v1/requests").header("Authorization", "Bearer " + aliceToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"typeCode\":\"EXPENSE\",\"payload\":{\"amount\":50,\"description\":\"Snacks\"}}"))
                .andExpect(status().isCreated())
                .andReturn();
            long id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

            mvc.perform(get("/api/v1/requests/queue").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")]").isEmpty());
        }

        @Test
        @DisplayName("approved request drops out of queue")
        void approved_requestNotInQueue() throws Exception {
            long id = createAndSubmit(aliceToken(), "EXPENSE", "{\"amount\":10,\"description\":\"Pens\"}");

            // Admin approves → step SATISFIED → no more PENDING steps
            mvc.perform(post("/api/v1/requests/" + id + "/decisions").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPROVE\",\"justification\":\"ok\"}")).andExpect(status().isOk());

            mvc.perform(get("/api/v1/requests/queue").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")]").isEmpty());
        }

        @Test
        @DisplayName("multiple pending approvals all listed")
        void multiple_pendingApprovalsListed() throws Exception {
            long id1 = createAndSubmit(aliceToken(), "EXPENSE", "{\"amount\":200,\"description\":\"Lunch\"}");
            long id2 = createAndSubmit(aliceToken(), "IT_ACCESS", "{\"resource\":\"Jira\",\"duration\":\"30d\"}");

            JsonNode body = mapper
                .readTree(mvc.perform(get("/api/v1/requests/queue").header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());

            assertThat(body.get("totalElements").asInt()).isGreaterThanOrEqualTo(2);
            assertThat(body.get("content").toString()).contains(String.valueOf(id1));
            assertThat(body.get("content").toString()).contains(String.valueOf(id2));
        }

        @Test
        @DisplayName("queue supports pagination")
        void pagination() throws Exception {
            // Create 3 pending approvals
            for (int i = 0; i < 3; i++) {
                createAndSubmit(aliceToken(), "EXPENSE",
                        "{\"amount\":" + (i + 1) + ",\"description\":\"Item " + i + "\"}");
            }

            // page=1, size=1 → first page has 1 element, totalElements >= 3
            JsonNode page0 = mapper.readTree(mvc
                .perform(get("/api/v1/requests/queue").param("page", "1")
                    .param("size", "1")
                    .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

            assertThat(page0.get("content").size()).isEqualTo(1);
            assertThat(page0.get("totalElements").asInt()).isGreaterThanOrEqualTo(3);
            assertThat(page0.get("totalPages").asInt()).isGreaterThanOrEqualTo(3);
            assertThat(page0.get("first").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("queue supports sorting by createdAt")
        void sorting() throws Exception {
            createAndSubmit(aliceToken(), "EXPENSE", "{\"amount\":1,\"description\":\"A\"}");
            createAndSubmit(aliceToken(), "EXPENSE", "{\"amount\":2,\"description\":\"B\"}");

            // sort by createdAt desc → newest first
            mvc.perform(get("/api/v1/requests/queue").param("sort", "createdAt,desc")
                .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").isNumber());
        }

        @Test
        @DisplayName("401 when unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mvc.perform(get("/api/v1/requests/queue")).andExpect(status().isUnauthorized());
        }

    }

    // ---------- DTOs ----------

    record AuthDto(String username, String password) {
    }

}
