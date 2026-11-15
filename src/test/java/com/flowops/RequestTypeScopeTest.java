package com.flowops;

import com.flowops.domain.Role;
import com.flowops.domain.User;
import com.flowops.infra.persistence.PermissionJpaRepository;
import com.flowops.infra.persistence.RoleJpaRepository;
import com.flowops.infra.persistence.UserJpaRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V12: Per-request-type dynamic permissions.
 *
 * <ul>
 *   <li>EXPENSE_CREATOR can create EXPENSE → 201</li>
 *   <li>EXPENSE_CREATOR cannot create IT_ACCESS → 403 with evidence</li>
 *   <li>MEMBER (global request:create) can create any type → 201</li>
 *   <li>Evidence trail shows scope mismatch on denial</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
class RequestTypeScopeTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserJpaRepository users;
    @Autowired private RoleJpaRepository roles;
    @Autowired private PermissionJpaRepository permissions;
    @Autowired private PasswordEncoder encoder;

    // ── helpers ────────────────────────────────────────────────────────

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

    private User createUser(String username, String password, List<String> roleCodes) {
        Set<Role> roleSet = roleCodes.stream()
                .map(code -> roles.findByCode(code)
                        .orElseThrow(() -> new IllegalStateException("Role not found: " + code)))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        User user = User.newUser(username, username, null, null, roleSet, encoder.encode(password));
        return users.save(user);
    }

    private int createRequest(String token, String typeCode, String payload) throws Exception {
        return mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"typeCode\":\"" + typeCode + "\",\"payload\":" + payload + "}"))
                .andReturn().getResponse().getStatus();
    }

    // ── tests ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Per-type create — catalog")
    class TypeScopedCatalog {

        @Test
        void typeScopedCreatePermissionsExist() {
            var all = permissions.findAll();
            var RT = com.flowops.domain.ScopeType.REQUEST_TYPE;
            // EXPENSE-scoped create
            assertThat(all).anySatisfy(p -> {
                assertThat(p.getCode()).isEqualTo("request:create");
                assertThat(p.getScopeType()).isEqualTo(RT);
                assertThat(p.getScopeValue()).isEqualTo("EXPENSE");
            });
            // IT_ACCESS-scoped create
            assertThat(all).anySatisfy(p -> {
                assertThat(p.getCode()).isEqualTo("request:create");
                assertThat(p.getScopeType()).isEqualTo(RT);
                assertThat(p.getScopeValue()).isEqualTo("IT_ACCESS");
            });
            // EQUIPMENT-scoped create
            assertThat(all).anySatisfy(p -> {
                assertThat(p.getCode()).isEqualTo("request:create");
                assertThat(p.getScopeType()).isEqualTo(RT);
                assertThat(p.getScopeValue()).isEqualTo("EQUIPMENT");
            });
            // POLICY_EXCEPTION-scoped create
            assertThat(all).anySatisfy(p -> {
                assertThat(p.getCode()).isEqualTo("request:create");
                assertThat(p.getScopeType()).isEqualTo(RT);
                assertThat(p.getScopeValue()).isEqualTo("POLICY_EXCEPTION");
            });
        }

        @Test
        void expenseCreatorRoleExists() {
            assertThat(roles.findByCode("EXPENSE_CREATOR")).isPresent();
        }
    }

    @Nested
    @DisplayName("Per-type create — authorization")
    class TypeScopedAuthz {

        @Test
        void expenseCreator_canCreateExpense() throws Exception {
            createUser("expense_only", "pass123456", List.of("EXPENSE_CREATOR"));
            String token = userToken("expense_only", "pass123456");

            int status = createRequest(token, "EXPENSE",
                    "{\"amount\":100,\"description\":\"test\"}");
            assertThat(status).as("EXPENSE_CREATOR should create EXPENSE").isEqualTo(201);
        }

        @Test
        void expenseCreator_cannotCreateITAccess() throws Exception {
            createUser("expense_only2", "pass123456", List.of("EXPENSE_CREATOR"));
            String token = userToken("expense_only2", "pass123456");

            int status = createRequest(token, "IT_ACCESS",
                    "{\"resource\":\"Jira\",\"duration\":\"30d\"}");
            assertThat(status).as("EXPENSE_CREATOR should NOT create IT_ACCESS").isEqualTo(403);
        }

        @Test
        void expenseCreator_cannotCreateEquipment() throws Exception {
            createUser("expense_only3", "pass123456", List.of("EXPENSE_CREATOR"));
            String token = userToken("expense_only3", "pass123456");

            int status = createRequest(token, "EQUIPMENT",
                    "{\"item\":\"Laptop\",\"quantity\":1}");
            assertThat(status).as("EXPENSE_CREATOR should NOT create EQUIPMENT").isEqualTo(403);
        }

        @Test
        void member_canCreateAnyType() throws Exception {
            // alice is a MEMBER (global request:create)
            String token = userToken("alice", "alice123");

            assertThat(createRequest(token, "EXPENSE",
                    "{\"amount\":50,\"description\":\"coffee\"}"))
                    .as("MEMBER should create EXPENSE").isEqualTo(201);

            assertThat(createRequest(token, "IT_ACCESS",
                    "{\"resource\":\"GitLab\",\"duration\":\"14d\"}"))
                    .as("MEMBER should create IT_ACCESS").isEqualTo(201);

            assertThat(createRequest(token, "EQUIPMENT",
                    "{\"item\":\"Monitor\",\"quantity\":2}"))
                    .as("MEMBER should create EQUIPMENT").isEqualTo(201);
        }

        @Test
        void denial_evidenceShowsScopeMismatch() throws Exception {
            createUser("expense_only4", "pass123456", List.of("EXPENSE_CREATOR"));
            String token = userToken("expense_only4", "pass123456");

            // Try to create IT_ACCESS → 403 with evidence
            String body = mvc.perform(post("/api/v1/requests")
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"typeCode\":\"IT_ACCESS\",\"payload\":{\"resource\":\"Jira\",\"duration\":\"30d\"}}"))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();

            // Evidence should mention the scope check
            assertThat(body).containsIgnoringCase("REQUEST_TYPE");
            assertThat(body).containsIgnoringCase("IT_ACCESS");
        }
    }
}
