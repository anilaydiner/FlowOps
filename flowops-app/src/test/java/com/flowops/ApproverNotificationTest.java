package com.flowops;

import com.flowops.domain.OutboxEvents;
import com.flowops.domain.OutboxMessage;
import com.flowops.domain.Role;
import com.flowops.domain.StepState;
import com.flowops.domain.User;
import com.flowops.infra.persistence.DepartmentJpaRepository;
import com.flowops.infra.persistence.OutboxMessageJpaRepository;
import com.flowops.infra.persistence.RoleJpaRepository;
import com.flowops.infra.persistence.StepInstanceJpaRepository;
import com.flowops.infra.persistence.UserJpaRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8: when a request enters review (or advances to a new step), every
 * user who passes the <b>same eligibility gate</b> as {@code DecideStep}
 * (permission + scope + SoD) gets a FEED + EMAIL notification via the
 * transactional outbox — and no one who cannot act is notified.
 *
 * <ul>
 *   <li>Submit → eligible approvers notified (scope-filtered)</li>
 *   <li>Advance to next step → approvers re-notified (per-step idempotency)</li>
 *   <li>Actor who advanced the workflow is not re-notified about it</li>
 *   <li>BLOCKED step (no eligible approver exists) → no notifications</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
class ApproverNotificationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserJpaRepository users;
    @Autowired private RoleJpaRepository roles;
    @Autowired private DepartmentJpaRepository departments;
    @Autowired private PasswordEncoder encoder;
    @Autowired private OutboxMessageJpaRepository outboxRepo;
    @Autowired private StepInstanceJpaRepository stepInstances;

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

    private int decide(String approverToken, long requestId, String action) throws Exception {
        return mvc.perform(post("/api/v1/requests/" + requestId + "/decisions")
                        .header("Authorization", "Bearer " + approverToken)
                        .contentType("application/json")
                        .content("{\"action\":\"" + action + "\",\"justification\":\"test\"}"))
                .andReturn().getResponse().getStatus();
    }

    // ── outbox query helpers ───────────────────────────────────────────

    private List<OutboxMessage> emailRows(long requestId) {
        return outboxRepo.findByKeyPrefix("req:" + requestId + ":" + OutboxEvents.APPROVER_REQUEST + ":");
    }

    private List<OutboxMessage> feedRows(long requestId) {
        return outboxRepo.findByKeyPrefix("req:" + requestId + ":" + OutboxEvents.NOTIFY_APPROVER_REQUEST + ":");
    }

    private boolean notifies(List<OutboxMessage> rows, String username) {
        Long id = users.findByUsername(username).orElseThrow().getId();
        return rows.stream().anyMatch(m -> id.equals(m.getRecipientId()));
    }

    private long rowCount(List<OutboxMessage> rows, String username) {
        Long id = users.findByUsername(username).orElseThrow().getId();
        return rows.stream().filter(m -> id.equals(m.getRecipientId())).count();
    }

    // ── workflow setup helpers (two-step + ghost) ──────────────────────

    /** Workflow with two steps (ADMIN then FINANCE_MANAGER), FINANCE-scoped type. */
    private String createDualType(String suffix) throws Exception {
        String admin = userToken("admin", "admin123");
        String wf = mvc.perform(post("/api/v1/workflows")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("""
                                {"code":"WF_D8_%s","name":"Dual 8","steps":[
                                  {"position":1,"code":"D8_STEP1","name":"Step 1","requiredRole":"ADMIN","completionRule":"ANY_OF","requiredApprovals":1},
                                  {"position":2,"code":"D8_STEP2","name":"Step 2","requiredRole":"FINANCE_MANAGER","completionRule":"ANY_OF","requiredApprovals":1}]}
                                """.formatted(suffix)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(wf).contains("WF_D8_" + suffix);

        mvc.perform(post("/api/v1/request-types")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("""
                                {"code":"DUAL8_%s","name":"Dual 8","departmentCode":"FINANCE",
                                 "workflowCode":"WF_D8_%s","requiredFields":{"note":"Why"}}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isCreated());
        return "DUAL8_" + suffix;
    }

    /** Single-step workflow whose required role nobody has → step materializes BLOCKED. */
    private String createGhostType(String suffix) throws Exception {
        String admin = userToken("admin", "admin123");
        mvc.perform(post("/api/v1/workflows")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("""
                                {"code":"WF_G8_%s","name":"Ghost 8","steps":[
                                  {"position":1,"code":"G8_STEP1","name":"Ghost Step","requiredRole":"GHOST8_ROLE_%s","completionRule":"ANY_OF","requiredApprovals":1}]}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/request-types")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("""
                                {"code":"GHOST8_%s","name":"Ghost 8","departmentCode":"FINANCE",
                                 "workflowCode":"WF_G8_%s","requiredFields":{"note":"Why"}}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isCreated());
        return "GHOST8_" + suffix;
    }

    // ── tests ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Submit → eligible approvers notified")
    class OnSubmit {

        @Test
        void notifiesEligible_notIneligibleOrRequester() throws Exception {
            createUser("d8_fm", List.of("FINANCE_MANAGER"), "FINANCE");
            createUser("d8_ja", List.of("JUNIOR_APPROVER"), null);
            createUser("d8_member", List.of("MEMBER"), "FINANCE");

            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"test\"}");

            List<OutboxMessage> emails = emailRows(id);
            List<OutboxMessage> feeds = feedRows(id);

            // Eligible (same gate as DecideStep)
            assertThat(notifies(emails, "admin")).as("admin (global approve)").isTrue();
            assertThat(notifies(emails, "d8_fm")).as("FINANCE dept manager").isTrue();
            assertThat(notifies(emails, "d8_ja")).as("JUNIOR_APPROVER within threshold").isTrue();
            // Not eligible
            assertThat(notifies(emails, "alice")).as("requester (SoD, no override)").isFalse();
            assertThat(notifies(emails, "d8_member")).as("MEMBER has no approve permission").isFalse();
            // Feed mirrors the email set
            assertThat(notifies(feeds, "admin")).isTrue();
            assertThat(notifies(feeds, "d8_fm")).isTrue();
            assertThat(notifies(feeds, "d8_ja")).isTrue();
            assertThat(notifies(feeds, "d8_member")).isFalse();
        }

        @Test
        void departmentScope_filtersByOwningDept() throws Exception {
            createUser("d8_fm", List.of("FINANCE_MANAGER"), "FINANCE");
            createUser("d8_it", List.of("FINANCE_MANAGER"), "IT");

            long id = createAndSubmitAlice("IT_ACCESS", "{\"resource\":\"Jira\",\"duration\":\"30d\"}");

            List<OutboxMessage> emails = emailRows(id);
            assertThat(notifies(emails, "d8_it")).as("IT dept manager").isTrue();
            assertThat(notifies(emails, "d8_fm")).as("FINANCE manager, IT request").isFalse();
        }

        @Test
        void thresholdScope_filtersAboveLimit() throws Exception {
            createUser("d8_fm", List.of("FINANCE_MANAGER"), "FINANCE");
            createUser("d8_ja", List.of("JUNIOR_APPROVER"), null);

            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":8000,\"description\":\"big\"}");

            List<OutboxMessage> emails = emailRows(id);
            assertThat(notifies(emails, "d8_fm")).as("dept manager has no threshold limit").isTrue();
            assertThat(notifies(emails, "d8_ja")).as("above 5000 threshold").isFalse();
        }

        @Test
        void emailPayload_containsSubjectRequestAndFeLink() throws Exception {
            long id = createAndSubmitAlice("EXPENSE", "{\"amount\":100,\"description\":\"test\"}");

            OutboxMessage adminRow = emailRows(id).stream()
                    .filter(m -> m.getRecipientId().equals(users.findByUsername("admin").orElseThrow().getId()))
                    .findFirst().orElseThrow(() -> new AssertionError("no APPROVER_REQUEST row for admin"));

            String payload = adminRow.getPayload();
            assertThat(payload).contains("awaits your approval");
            assertThat(payload).contains("#" + id);
            assertThat(payload).contains("http://localhost:5173/requests/" + id);
        }
    }

    @Nested
    @DisplayName("Workflow advance → next step's approvers re-notified")
    class OnAdvance {

        @Test
        void eachStepNotifiesPerStep_actorNotReNotified() throws Exception {
            createUser("d8_fm", List.of("FINANCE_MANAGER"), "FINANCE");
            createUser("d8_ja", List.of("JUNIOR_APPROVER"), null);

            String typeCode = createDualType("ADV");
            long id = createAndSubmitAlice(typeCode, "{\"note\":\"dual step test\"}");

            // Step 1 pending → approvers notified (step 1)
            long fmAfterStep1 = rowCount(emailRows(id), "d8_fm");
            assertThat(fmAfterStep1).as("fm notified at step 1").isEqualTo(1);

            // Admin approves step 1 → step 2 materializes → approvers re-notified
            int status = decide(userToken("admin", "admin123"), id, "APPROVE");
            assertThat(status).isEqualTo(200);

            List<OutboxMessage> emails = emailRows(id);
            assertThat(rowCount(emails, "d8_fm")).as("fm notified at step 1 AND step 2").isEqualTo(2);
            assertThat(rowCount(emails, "d8_ja")).as("ja notified at step 1 AND step 2").isEqualTo(2);
            // Admin acted on step 1 and is excluded from the step-2 nudge
            assertThat(rowCount(emails, "admin")).as("actor not re-notified").isEqualTo(1);

            // Request is still in review (step 2 pending), not approved
            assertThat(stepInstances.countByRequestIdAndState(id, StepState.PENDING)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Blocked step → no approver notifications")
    class OnBlocked {

        @Test
        void blockedStep_notifiesNoOne() throws Exception {
            String typeCode = createGhostType("BLK");
            long id = createAndSubmitAlice(typeCode, "{\"note\":\"ghost test\"}");

            // The step materialized BLOCKED (no active user with GHOST8_ROLE)
            assertThat(stepInstances.findByRequestIdOrderById(id))
                    .anySatisfy(si -> assertThat(si.getState()).isEqualTo(StepState.BLOCKED));

            // Nobody was notified — there is no eligible approver to reach
            assertThat(emailRows(id)).as("no APPROVER_REQUEST emails").isEmpty();
            assertThat(feedRows(id)).as("no NOTIFY_APPROVER_REQUEST feeds").isEmpty();
        }
    }
}
