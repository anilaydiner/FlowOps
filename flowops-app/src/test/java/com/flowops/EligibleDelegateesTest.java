package com.flowops;

import com.flowops.domain.Role;
import com.flowops.domain.User;
import com.flowops.domain.port.DepartmentRepository;
import com.flowops.domain.port.RoleRepository;
import com.flowops.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7: Delegation must only target users who can actually approve the request.
 *
 * <ul>
 *   <li>GET /requests/{id}/eligible-delegatees — same gate as approval
 *       (permission + scope + SoD), excludes caller and requester.</li>
 *   <li>Only the current approver may list delegates (403 otherwise).</li>
 *   <li>DELEGATE validates the delegatee: not self (409), exists (404),
 *       active (409), eligible (403 with evidence).</li>
 * </ul>
 *
 * <p>Assertions use "contains / does not contain" rather than exact-list
 * matches because other (non-transactional) test classes commit users to
 * the shared Testcontainers database.</p>
 */
@AutoConfigureMockMvc
@Transactional
class EligibleDelegateesTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private DepartmentRepository departments;
    @Autowired private PasswordEncoder encoder;

    private static final String PASSWORD = "pass123456";

    // ── helpers ────────────────────────────────────────────────────────

    private User createUser(String username, List<String> roleCodes, String deptCode) {
        Set<Role> roleSet = new LinkedHashSet<>();
        for (String code : roleCodes) {
            roleSet.add(roles.findByCode(code)
                    .orElseThrow(() -> new IllegalStateException("Role not found: " + code)));
        }
        com.flowops.domain.Department dept = (deptCode != null)
                ? departments.findByCode(deptCode)
                        .orElseThrow(() -> new IllegalStateException("Dept not found: " + deptCode))
                : null;
        User user = User.newUser(username, username, null, dept, roleSet, encoder.encode(PASSWORD));
        return users.save(user);
    }

    private String userToken(String username, String password) throws Exception {
        String json = mvc.perform(post("/api/v1/auth/token")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int i = json.indexOf("\"token\":\"");
        String t = json.substring(i + "\"token\":\"".length());
        return t.substring(0, t.indexOf('"'));
    }

    private long extractId(String json) {
        int i = json.indexOf("\"id\":");
        String n = json.substring(i + 5);
        int end = n.indexOf(',');
        if (end < 0) end = n.indexOf('}');
        return Long.parseLong(n.substring(0, end).trim());
    }

    /** Alice (MEMBER) creates + submits a request of the given type/payload. */
    private long createAndSubmitAlice(String typeCode, String payload) throws Exception {
        String alice = userToken("alice", "alice123");
        String body = mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + alice)
                        .contentType("application/json")
                        .content("{\"typeCode\":\"" + typeCode + "\",\"payload\":" + payload + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = extractId(body);
        mvc.perform(post("/api/v1/requests/" + id + "/submit")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());
        return id;
    }

    /** Alice (MEMBER) creates a DRAFT (not submitted). */
    private long createDraftAlice(String typeCode, String payload) throws Exception {
        String alice = userToken("alice", "alice123");
        String body = mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + alice)
                        .contentType("application/json")
                        .content("{\"typeCode\":\"" + typeCode + "\",\"payload\":" + payload + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return extractId(body);
    }

    private int eligibleStatus(String callerToken, long requestId) throws Exception {
        return mvc.perform(get("/api/v1/requests/" + requestId + "/eligible-delegatees")
                        .header("Authorization", "Bearer " + callerToken))
                .andReturn().getResponse().getStatus();
    }

    private boolean eligibleListContains(String callerToken, long requestId, String username) throws Exception {
        String body = mvc.perform(get("/api/v1/requests/" + requestId + "/eligible-delegatees")
                        .header("Authorization", "Bearer " + callerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.contains("\"username\":\"" + username + "\"");
    }

    private int delegate(String approverToken, long requestId, long delegatedTo) throws Exception {
        return mvc.perform(post("/api/v1/requests/" + requestId + "/decisions")
                        .header("Authorization", "Bearer " + approverToken)
                        .contentType("application/json")
                        .content("{\"action\":\"DELEGATE\",\"justification\":\"on PTO\",\"delegatedTo\":" + delegatedTo + "}"))
                .andReturn().getResponse().getStatus();
    }

    // ── eligible-delegatees endpoint ───────────────────────────────────

    @Nested
    @DisplayName("GET /requests/{id}/eligible-delegatees")
    class Endpoint {

        @Test
        void listsEligibleUsers_excludesRequesterSelfAndIneligible() throws Exception {
            createUser("d7_fm", List.of("FINANCE_MANAGER"), "FINANCE");
            createUser("d7_ja", List.of("JUNIOR_APPROVER"), null);
            createUser("d7_member", List.of("MEMBER"), "FINANCE");

            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"test\"}");

            assertThat(eligibleStatus(admin, id)).isEqualTo(200);

            // Eligible: d7_fm (dept FINANCE), d7_ja (threshold 5000 ≥ 100)
            assertThat(eligibleListContains(admin, id, "d7_fm")).as("FINANCE_MANAGER should be eligible").isTrue();
            assertThat(eligibleListContains(admin, id, "d7_ja")).as("JUNIOR_APPROVER should be eligible").isTrue();
            // Excluded: caller (self), requester alice, member without approve permission
            assertThat(eligibleListContains(admin, id, "admin")).as("caller must be excluded (self)").isFalse();
            assertThat(eligibleListContains(admin, id, "alice")).as("requester must be excluded").isFalse();
            assertThat(eligibleListContains(admin, id, "d7_member")).as("MEMBER has no approve permission").isFalse();
        }

        @Test
        void thresholdScope_filtersLargeRequests() throws Exception {
            createUser("d7_ja_big", List.of("JUNIOR_APPROVER"), null);
            createUser("d7_fm_big", List.of("FINANCE_MANAGER"), "FINANCE");

            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":8000,\"description\":\"big\"}");

            assertThat(eligibleListContains(admin, id, "d7_fm_big")).as("dept-scoped manager eligible").isTrue();
            assertThat(eligibleListContains(admin, id, "d7_ja_big"))
                    .as("JUNIOR_APPROVER above 5000 threshold must NOT be eligible").isFalse();
            assertThat(eligibleListContains(admin, id, "admin")).as("caller must be excluded (self)").isFalse();
        }

        @Test
        void departmentScope_filtersByDept() throws Exception {
            // Same role (dept-scoped approve) in two departments: only the IT one qualifies
            createUser("d7_fm_it", List.of("FINANCE_MANAGER"), "IT");
            createUser("d7_fm_fin", List.of("FINANCE_MANAGER"), "FINANCE");

            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("IT_ACCESS", "{\"resource\":\"Jira\",\"duration\":\"30d\"}");

            assertThat(eligibleListContains(admin, id, "d7_fm_it"))
                    .as("IT-dept manager should be eligible for IT request").isTrue();
            assertThat(eligibleListContains(admin, id, "d7_fm_fin"))
                    .as("FINANCE manager must NOT be eligible for IT dept request").isFalse();
        }

        @Test
        void nonApproverCaller_gets403() throws Exception {
            String alice = userToken("alice", "alice123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"test\"}");

            assertThat(eligibleStatus(alice, id)).isEqualTo(403);
        }

        @Test
        void draftRequest_gets409() throws Exception {
            String admin = userToken("admin", "admin123");
            long draftId = createDraftAlice("EXPENSE", "{\"amount\":100,\"description\":\"draft\"}");

            assertThat(eligibleStatus(admin, draftId)).isEqualTo(409);
        }
    }

    // ── DELEGATE validation in DecideStep ──────────────────────────────

    @Nested
    @DisplayName("DELEGATE validates the delegatee")
    class DelegateValidation {

        @Test
        void delegateToSelf_rejected409() throws Exception {
            String admin = userToken("admin", "admin123");
            long adminId = users.findByUsername("admin").orElseThrow().getId();
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"test\"}");

            assertThat(delegate(admin, id, adminId)).isEqualTo(409);
        }

        @Test
        void delegateToUnknownUser_rejected404() throws Exception {
            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"test\"}");

            assertThat(delegate(admin, id, 99_999_999L)).isEqualTo(404);
        }

        @Test
        void delegateToInactiveUser_rejected409() throws Exception {
            User inactive = createUser("d7_inactive", List.of("ADMIN"), null);
            inactive.setActive(false);
            users.save(inactive);

            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"test\"}");

            assertThat(delegate(admin, id, inactive.getId())).isEqualTo(409);
        }

        @Test
        void delegateToUserWithoutApprovePermission_rejected403WithEvidence() throws Exception {
            User member = createUser("d7_member2", List.of("MEMBER"), "FINANCE");

            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"test\"}");

            String body = mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + admin)
                            .contentType("application/json")
                            .content("{\"action\":\"DELEGATE\",\"justification\":\"x\",\"delegatedTo\":" + member.getId() + "}"))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).containsIgnoringCase("not eligible");
        }

        @Test
        void delegateToEligibleUser_succeeds() throws Exception {
            User fm = createUser("d7_fm_ok", List.of("FINANCE_MANAGER"), "FINANCE");

            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"test\"}");

            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + admin)
                            .contentType("application/json")
                            .content("{\"action\":\"DELEGATE\",\"justification\":\"on PTO\",\"delegatedTo\":" + fm.getId() + "}"))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));
        }

        @Test
        void endpointAndDelegateValidation_agree() throws Exception {
            // Consistency: the endpoint and the DELEGATE gate must agree —
            // a user the endpoint lists is accepted, one it excludes is rejected.
            User fm = createUser("d7_fm_cx", List.of("FINANCE_MANAGER"), "FINANCE");
            User ja = createUser("d7_ja_cx", List.of("JUNIOR_APPROVER"), null);
            User member = createUser("d7_member_cx", List.of("MEMBER"), "FINANCE");

            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":8000,\"description\":\"big\"}");

            // 8000 > 5000 threshold → ja not listed; fm listed; member not listed
            assertThat(eligibleListContains(admin, id, "d7_fm_cx")).as("fm should be listed").isTrue();
            assertThat(eligibleListContains(admin, id, "d7_ja_cx")).as("ja should not be listed (above threshold)").isFalse();
            assertThat(eligibleListContains(admin, id, "d7_member_cx")).as("member should not be listed").isFalse();

            // DELEGATE must agree: fm ok, ja 403, member 403
            assertThat(delegate(admin, id, fm.getId())).as("DELEGATE to listed fm should succeed").isLessThan(300);
            assertThat(delegate(admin, id, ja.getId())).isEqualTo(403);
            assertThat(delegate(admin, id, member.getId())).isEqualTo(403);
        }
    }

    // ── delegation → queue visibility + approval ──────────────────────

    @Nested
    @DisplayName("DELEGATE makes the request visible and actionable for the delegatee")
    class DelegationVisibility {

        @Test
        void delegateeSeesRequestInQueue_canOpenDetail_andCanApprove() throws Exception {
            User fm = createUser("d8_fm", List.of("FINANCE_MANAGER"), "FINANCE");

            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"delegated\"}");

            // admin delegates the decision to the FINANCE_MANAGER
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + admin)
                            .contentType("application/json")
                            .content("{\"action\":\"DELEGATE\",\"justification\":\"on PTO\",\"delegatedTo\":" + fm.getId() + "}"))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));

            String fmToken = userToken("d8_fm", PASSWORD);

            // 1) the delegatee sees the request in their queue
            //    (via the DELEGATE branch — the step's required role is ADMIN)
            String queue = mvc.perform(get("/api/v1/requests/queue")
                            .header("Authorization", "Bearer " + fmToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(queue).as("delegatee queue must contain the delegated request")
                    .contains("\"id\":" + id + ",");

            // 2) the delegatee can open the request detail
            mvc.perform(get("/api/v1/requests/" + id)
                            .header("Authorization", "Bearer " + fmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));

            // 3) the delegatee can approve — dept-scoped request:approve passes,
            //    and as the only step for amount=100 the request becomes APPROVED
            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + fmToken)
                            .contentType("application/json")
                            .content("{\"action\":\"APPROVE\",\"justification\":\"looks fine\"}"))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }

        @Test
        void sameRole_notDelegated_doesNotSeeRequestInQueue() throws Exception {
            User fmDelegated = createUser("d8_fm_ok", List.of("FINANCE_MANAGER"), "FINANCE");
            User fmOther = createUser("d8_fm_other", List.of("FINANCE_MANAGER"), "FINANCE");

            String admin = userToken("admin", "admin123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"delegated\"}");

            mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + admin)
                            .contentType("application/json")
                            .content("{\"action\":\"DELEGATE\",\"justification\":\"on PTO\",\"delegatedTo\":" + fmDelegated.getId() + "}"))
                    .andExpect(status().is2xxSuccessful());

            // Visibility comes from the DELEGATE decision, not from the role:
            // fmOther has the same role + department but was not delegated to.
            String queue = mvc.perform(get("/api/v1/requests/queue")
                            .header("Authorization", "Bearer " + userToken("d8_fm_other", PASSWORD)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(queue).as("non-delegatee queue must NOT contain the request")
                    .doesNotContain("\"id\":" + id + ",");
        }

        @Test
        void requester_neverSeesOwnRequestInQueue() throws Exception {
            // MEMBER holds request:queue; SoD still keeps own requests out
            String alice = userToken("alice", "alice123");
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"own\"}");

            String queue = mvc.perform(get("/api/v1/requests/queue")
                            .header("Authorization", "Bearer " + alice))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(queue).as("requester queue must NOT contain own request")
                    .doesNotContain("\"id\":" + id + ",");
        }
    }
}
