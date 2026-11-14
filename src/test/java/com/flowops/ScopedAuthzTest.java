package com.flowops;

import com.flowops.domain.Permission;
import com.flowops.domain.Role;
import com.flowops.domain.User;
import com.flowops.domain.UserRepository;
import com.flowops.domain.RoleRepository;
import com.flowops.domain.PermissionRepository;
import com.flowops.domain.DepartmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Iteration 5: Scoped Authorization + Eligibility (PLAN §7).
 *
 * <ul>
 *   <li>SELF scope — requester can close own, cannot approve own (SoD)</li>
 *   <li>DEPARTMENT scope — only in user's department; departmentless → deny</li>
 *   <li>THRESHOLD scope — approve up to amount; exceeded → deny</li>
 *   <li>Evidence — explainable trail in decisions.evidence</li>
 *   <li>SoD override — request:override:sod permits self-approval</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
class ScopedAuthzTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private DepartmentRepository departments;
    @Autowired private PasswordEncoder encoder;

    // ── helpers ────────────────────────────────────────────────────────

    private String adminToken() throws Exception {
        return userToken("admin", "admin123");
    }

    private String token(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/token")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String extractToken(String json) {
        int i = json.indexOf("\"token\":\"");
        String t = json.substring(i + "\"token\":\"".length());
        return t.substring(0, t.indexOf('"'));
    }

    private static long extractId(String json) {
        int i = json.indexOf("\"id\":");
        String n = json.substring(i + 5);
        int end = n.indexOf(',');
        if (end < 0) end = n.indexOf('}');
        return Long.parseLong(n.substring(0, end).trim());
    }

    /** Create a user with specific roles and optional department. */
    private User createUser(String username, String password, List<String> roleCodes, String deptCode) {
        Set<Role> roleSet = roleCodes.stream()
                .map(code -> roles.findByCode(code)
                        .orElseThrow(() -> new IllegalStateException("Role not found: " + code)))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        com.flowops.domain.Department dept = (deptCode != null)
                ? departments.findByCode(deptCode)
                        .orElseThrow(() -> new IllegalStateException("Dept not found: " + deptCode))
                : null;
        User user = User.newUser(username, username, null, dept, roleSet, encoder.encode(password));
        return users.save(user);
    }

    private String userToken(String username, String password) throws Exception {
        String json = mvc.perform(post("/api/v1/auth/token")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractToken(json);
    }

    /**
     * Create a draft + submit for a given requester.
     * The payload must match the required_fields for the type:
     *   EXPENSE:  {"amount": <number>, "description": "<string>"}
     *   IT_ACCESS: {"resource": "<string>", "duration": "<e.g. 30d>"}
     */
    private long createAndSubmit(String requesterToken, String typeCode, String payload) throws Exception {
        String body = mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + requesterToken)
                        .contentType("application/json")
                        .content("{\"typeCode\":\"" + typeCode + "\",\"payload\":" + payload + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = extractId(body);

        mvc.perform(post("/api/v1/requests/" + id + "/submit")
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk());
        return id;
    }

    // Convenience overloads for the most common types
    private long createAndSubmit(String requesterToken, String typeCode, double amount) throws Exception {
        return createAndSubmit(requesterToken, typeCode,
                "{\"amount\":" + amount + ",\"description\":\"test expense\"}");
    }

    private int decide(String approverToken, long requestId, String action) throws Exception {
        return mvc.perform(post("/api/v1/requests/" + requestId + "/decisions")
                        .header("Authorization", "Bearer " + approverToken)
                        .contentType("application/json")
                        .content("{\"action\":\"" + action + "\",\"justification\":\"test\"}"))
                .andReturn().getResponse().getStatus();
    }

    // ── tests ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scoped permissions — catalog")
    class PermissionCatalog {

        @Test
        void scopedPermissionsExist() {
            List<Permission> all = permissions.findAll();
            assertThat(all).anySatisfy(p -> {
                assertThat(p.getCode()).isEqualTo("request:approve:dept");
                assertThat(p.getScopeType()).isEqualTo(com.flowops.domain.ScopeType.DEPARTMENT);
            });
            assertThat(all).anySatisfy(p -> {
                assertThat(p.getCode()).isEqualTo("request:approve:threshold");
                assertThat(p.getScopeType()).isEqualTo(com.flowops.domain.ScopeType.THRESHOLD);
                assertThat(p.getScopeValue()).isEqualTo("5000");
            });
            assertThat(all).anySatisfy(p -> {
                assertThat(p.getCode()).isEqualTo("request:close:own");
                assertThat(p.getScopeType()).isEqualTo(com.flowops.domain.ScopeType.SELF);
            });
            assertThat(all).anySatisfy(p -> {
                assertThat(p.getCode()).isEqualTo("request:override:sod");
            });
        }

        @Test
        void newRolesExist() {
            assertThat(roles.findByCode("FINANCE_MANAGER")).isPresent();
            assertThat(roles.findByCode("JUNIOR_APPROVER")).isPresent();
        }
    }

    @Nested
    @DisplayName("SoD + evidence")
    class SeparationOfDuties {

        @Test
        void requesterCannotApproveOwn_evidenceShowsSoD() throws Exception {
            String admin = adminToken();
            // Alice creates + submits
            String alice = userToken("alice", "alice123");
            long id = createAndSubmit(alice, "EXPENSE", 100);

            // Alice tries to approve her own request → 403
            int status = decide(alice, id, "APPROVE");
            assertThat(status).isEqualTo(403);

            // Verify the 403 body mentions SoD
            String body = mvc.perform(post("/api/v1/requests/" + id + "/decisions")
                            .header("Authorization", "Bearer " + alice)
                            .contentType("application/json")
                            .content("{\"action\":\"APPROVE\",\"justification\":\"self\"}"))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).containsIgnoringCase("SoD");
        }

        @Test
        void soDOverride_allowsSelfApproval() throws Exception {
            String admin = adminToken();
            // Create a user with ADMIN + request:override:sod
            User overrideUser = createUser("override1", "pass123456", List.of("ADMIN"), null);
            // Grant request:override:sod explicitly
            Role adminRole = roles.findByCode("ADMIN").orElseThrow();
            Permission sodOverride = permissions.findAll().stream()
                    .filter(p -> p.getCode().equals("request:override:sod"))
                    .findFirst().orElseThrow();
            if (!adminRole.getPermissions().contains(sodOverride)) {
                adminRole.getPermissions().add(sodOverride);
                roles.save(adminRole);
            }

            String overrideToken = userToken("override1", "pass123456");
            long id = createAndSubmit(overrideToken, "EXPENSE", 100);

            // Override user approves their own request → should succeed (200)
            int status = decide(overrideToken, id, "APPROVE");
            assertThat(status).as("SoD override should allow self-approval").isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("DEPARTMENT scope")
    class DepartmentScope {

        @Test
        void departmentManager_approvesOwnDept() throws Exception {
            // FINANCE_MANAGER in FINANCE dept approves FINANCE request
            String admin = adminToken();
            User fm = createUser("fm1", "pass123456", List.of("FINANCE_MANAGER"), "FINANCE");
            String fmToken = userToken("fm1", "pass123456");

            String alice = userToken("alice", "alice123");
            long id = createAndSubmit(alice, "EXPENSE", 100);

            // FINANCE_MANAGER with FINANCE dept can approve EXPENSE (FINANCE dept)
            int status = decide(fmToken, id, "APPROVE");
            assertThat(status).as("Dept manager should approve own-dept request").isEqualTo(200);
        }

        @Test
        void departmentManager_cannotApproveOtherDept() throws Exception {
            // FINANCE_MANAGER in FINANCE dept tries to approve IT request
            String admin = adminToken();
            User fm = createUser("fm2", "pass123456", List.of("FINANCE_MANAGER"), "FINANCE");
            String fmToken = userToken("fm2", "pass123456");

            String alice = userToken("alice", "alice123");
            long id = createAndSubmit(alice, "IT_ACCESS", "{\"resource\":\"Jira\",\"duration\":\"30d\"}");

            // FINANCE_MANAGER cannot approve IT dept request
            int status = decide(fmToken, id, "APPROVE");
            assertThat(status).as("Dept manager should NOT approve other-dept request").isEqualTo(403);
        }

        @Test
        void departmentlessUser_cannotApproveDeptScoped() throws Exception {
            // FINANCE_MANAGER with NO department → default deny
            String admin = adminToken();
            User fm = createUser("fm3", "pass123456", List.of("FINANCE_MANAGER"), null);
            String fmToken = userToken("fm3", "pass123456");

            String alice = userToken("alice", "alice123");
            long id = createAndSubmit(alice, "EXPENSE", 100);

            int status = decide(fmToken, id, "APPROVE");
            assertThat(status).as("Departmentless user should be denied (default deny)").isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("THRESHOLD scope")
    class ThresholdScope {

        @Test
        void juniorApprover_withinThreshold() throws Exception {
            // JUNIOR_APPROVER can approve up to 5000
            String admin = adminToken();
            User ja = createUser("ja1", "pass123456", List.of("JUNIOR_APPROVER"), null);
            String jaToken = userToken("ja1", "pass123456");

            String alice = userToken("alice", "alice123");
            long id = createAndSubmit(alice, "EXPENSE", 3000);

            int status = decide(jaToken, id, "APPROVE");
            assertThat(status).as("Within threshold should be allowed").isEqualTo(200);
        }

        @Test
        void juniorApprover_exceedsThreshold() throws Exception {
            // JUNIOR_APPROVER cannot approve above 5000
            String admin = adminToken();
            User ja = createUser("ja2", "pass123456", List.of("JUNIOR_APPROVER"), null);
            String jaToken = userToken("ja2", "pass123456");

            String alice = userToken("alice", "alice123");
            long id = createAndSubmit(alice, "EXPENSE", 8000);

            int status = decide(jaToken, id, "APPROVE");
            assertThat(status).as("Exceeding threshold should be denied").isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("Evidence in decisions")
    class EvidenceTrail {

        @Test
        void approvedDecision_hasEvidence() throws Exception {
            String admin = adminToken();
            String alice = userToken("alice", "alice123");
            long id = createAndSubmit(alice, "EXPENSE", 100);

            // Admin approves (has global request:approve)
            int status = decide(admin, id, "APPROVE");
            assertThat(status).isEqualTo(200);

            // Fetch the request detail and check evidence
            String body = mvc.perform(get("/api/v1/requests/" + id)
                            .header("Authorization", "Bearer " + alice))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // The response should contain evidence
            assertThat(body).containsIgnoringCase("evidence");
            assertThat(body).containsIgnoringCase("permission");
        }
    }
}
