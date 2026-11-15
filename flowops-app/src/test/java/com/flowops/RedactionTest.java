package com.flowops;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowops.infra.persistence.DepartmentJpaRepository;
import com.flowops.infra.persistence.RoleJpaRepository;
import com.flowops.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Iteration 8 tests: secret redaction (PLAN §8).
 *
 * <ul>
 *   <li>User API responses never expose password hashes / credential fields</li>
 *   <li>Auth failure responses never echo the submitted password</li>
 *   <li>Rejected bearer tokens never appear in application logs
 *       (only the rejection reason + length)</li>
 *   <li>Rejected tokens are not reflected back in 401 responses</li>
 *   <li>Validation errors name the field, not the offending value</li>
 * </ul>
 */
@AutoConfigureMockMvc
class RedactionTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired com.flowops.infra.persistence.UserJpaRepository users;
    @Autowired com.flowops.infra.persistence.RoleJpaRepository roles;
    @Autowired com.flowops.infra.persistence.DepartmentJpaRepository departments;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    private String hankToken;
    private String adminToken;
    private long hankId;

    @BeforeEach
    void setup() throws Exception {
        if (users.findByUsername("it8_hank").isEmpty()) {
            com.flowops.domain.User hank = com.flowops.domain.User.newUser(
                    "it8_hank", "It8 Hank", "it8_hank@example.com",
                    departments.findByCode("IT").orElse(null),
                    java.util.Set.of(roles.findByCode("MEMBER").orElseThrow()), encoder.encode("it8_hank123"));
            users.save(hank);
        }
        hankToken = userToken("it8_hank", "it8_hank123");
        adminToken = userToken("admin", "admin123");
        hankId = extractId(mvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + hankToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

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

    private long extractId(String json) {
        int idx = json.indexOf("\"id\":");
        return Long.parseLong(json.substring(idx + 5).split("[,}\\s]")[0].trim());
    }

    private static void assertNoCredentialMaterial(String body, String... labels) {
        String lower = body.toLowerCase();
        for (String label : new String[]{"password", "password_hash", "passwordhash",
                "hash", "secret", "credential", "private_key", "bearer "}) {
            assertThat(lower)
                    .as("%s: response must not contain '%s'", String.join(",", labels), label)
                    .doesNotContain(label);
        }
    }

    // ── API responses ────────────────────────────────────────────────────

    @Test
    void userResponsesExposeNoCredentialMaterial() throws Exception {
        MvcResult me = mvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + hankToken))
                .andExpect(status().isOk())
                .andReturn();
        assertNoCredentialMaterial(me.getResponse().getContentAsString(), "GET /users/me");

        MvcResult byId = mvc.perform(get("/api/v1/users/" + hankId)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertNoCredentialMaterial(byId.getResponse().getContentAsString(), "GET /users/{id}");
    }

    @Test
    void authFailureDoesNotEchoPassword() throws Exception {
        String wrongPassword = "definitely-wrong-pw-42";
        MvcResult res = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it8_hank\",\"password\":\"" + wrongPassword + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(body).doesNotContain(wrongPassword);
        assertThat(body).doesNotContain("it8_hank123"); // nor any other password
    }

    @Test
    void rejectedTokenIsNotReflectedInResponse() throws Exception {
        String garbageToken = "garbage.token.value.it8-secret";
        MvcResult res = mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + garbageToken))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain(garbageToken);
    }

    @Test
    void validationErrorNamesFieldNotValue() throws Exception {
        // Admin creates a user with an (invalid) short password.
        String badPassword = "x9short";
        MvcResult res = mvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it8_newbie\",\"displayName\":\"Newbie\","
                                + "\"email\":\"it8_newbie@example.com\",\"roles\":[\"MEMBER\"],"
                                + "\"password\":\"" + badPassword + "\"}"))
                .andReturn();
        if (res.getResponse().getStatus() == 400) {
            String body = res.getResponse().getContentAsString();
            assertThat(body).doesNotContain(badPassword);
        } else {
            // Username already exists from a prior run → 409 is fine, but
            // still must not leak the password.
            assertThat(res.getResponse().getStatus()).isIn(409);
            assertThat(res.getResponse().getContentAsString()).doesNotContain(badPassword);
        }
    }

    // ── Log redaction ────────────────────────────────────────────────────

    @Test
    void rejectedTokenNeverAppearsInLogs() {
        String garbageToken = "super-secret-garbage-token.AB12.cd34";

        Logger jwtLogger = (Logger) LoggerFactory.getLogger(JwtService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        jwtLogger.addAppender(appender);
        try {
            assertThat(jwtService.parse(garbageToken)).isEmpty();
        } finally {
            jwtLogger.detachAppender(appender);
        }

        // The rejection must be logged (reason + length)…
        assertThat(appender.list).isNotEmpty();
        // …but never with the token content itself.
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).as("log line: {}", event.getFormattedMessage())
                    .doesNotContain(garbageToken);
            assertThat(event.getFormattedMessage()).doesNotContain("AB12.cd34");
        }
        // And the rejection reason is still visible (only the secret is gone).
        assertThat(appender.list)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).containsIgnoringCase("token rejected"));
    }

    @Test
    void validTokenParseDoesNotLogToken() {
        String token = userTokenOrNull("it8_hank", "it8_hank123");
        assertThat(token).isNotBlank();

        Logger jwtLogger = (Logger) LoggerFactory.getLogger(JwtService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        jwtLogger.addAppender(appender);
        try {
            assertThat(jwtService.parse(token)).isPresent();
        } finally {
            jwtLogger.detachAppender(appender);
        }

        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(token);
        }
    }

    private String userTokenOrNull(String username, String password) {
        try {
            return userToken(username, password);
        } catch (Exception e) {
            return null;
        }
    }
}
