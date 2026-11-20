package com.flowops;

import com.flowops.domain.identity.port.UserRepository;
import com.flowops.security.UserPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@AutoConfigureMockMvc
class ExecutionRolloutApiTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private org.springframework.security.core.Authentication auth(String name) {
        var u = users.findByUsername(name).orElseThrow();
        return new org.springframework.security.authentication.TestingAuthenticationToken(
                new UserPrincipal(u.getId(), name, Set.of("ADMIN"), null), null, "ROLE_TEST");
    }

    @Test
    void reportIsReadOnlyAndRequiresCurrentGlobalAuthority() throws Exception {
        Long events = jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events", Long.class);
        Long commands = jdbc.queryForObject("SELECT count(*) FROM execution_recovery_commands", Long.class);
        mvc.perform(get("/api/v1/execution/rollout").with(authentication(auth("admin"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phase").value("PRE_ENABLE"))
            .andExpect(jsonPath("$.manualAcceptanceRequired").isArray())
            .andExpect(jsonPath("$.contracts").isArray());
        mvc.perform(get("/api/v1/execution/rollout?phase=POST_ENABLE").with(authentication(auth("admin"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phase").value("POST_ENABLE"));
        mvc.perform(get("/api/v1/execution/rollout").with(authentication(auth("alice"))))
            .andExpect(status().isForbidden());
        assertEquals(events, jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events", Long.class));
        assertEquals(commands, jdbc.queryForObject("SELECT count(*) FROM execution_recovery_commands", Long.class));
    }

}
