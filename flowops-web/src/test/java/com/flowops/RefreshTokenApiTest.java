package com.flowops;

import com.flowops.application.identity.session.AuthenticationService;
import com.flowops.application.identity.session.ExpiredSessionCleanup;
import com.flowops.security.JwtService;
import com.flowops.application.identity.session.InvalidRefreshTokenException;
import com.flowops.application.identity.session.TokenPair;
import com.flowops.application.identity.session.TokenSecrets;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.port.RoleRepository;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "flowops.jwt.expiration-seconds=60", "flowops.auth.refresh-token-lifetime=2h",
        "flowops.auth.maximum-session-lifetime=3h" })
@AutoConfigureMockMvc
class RefreshTokenApiTest extends AbstractIntegrationTest {

    @MockitoBean
    Clock clock;

    @MockitoBean
    JobWorker backgroundJobs;

    @MockitoBean
    OutboxDispatcher backgroundOutbox;

    @MockitoBean
    SlaScanner backgroundSla;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthenticationService authentication;

    @Autowired
    ExpiredSessionCleanup cleanup;

    @Autowired
    JwtService jwt;

    @Autowired
    UserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    PlatformTransactionManager transactions;

    private final AtomicReference<Instant> now = new AtomicReference<>();

    @BeforeEach
    void setClock() {
        now.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        when(clock.instant()).thenAnswer(invocation -> now.get());
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mvc
            .perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Pragma", "no-cache"))
            .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode login() throws Exception {
        return login("admin", "admin123");
    }

    private String access(JsonNode pair) {
        return pair.get("token").asText();
    }

    private String refresh(JsonNode pair) {
        return pair.get("refreshToken").asText();
    }

    private String body(String token) {
        return json.writeValueAsString(Map.of("refreshToken", token));
    }

    private JsonNode rotate(String raw) throws Exception {
        var result = mvc
            .perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body(raw)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void refreshRejected(String raw) throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body(raw)))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(raw))));
    }

    private void accessible(String token, boolean expected) throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(expected ? status().isOk() : status().isUnauthorized());
    }

    private User uniqueUser() {
        return new TransactionTemplate(transactions).execute(status -> {
            String name = "refresh_" + UUID.randomUUID().toString().substring(0, 8);
            return users.save(User.newUser(name, "Session Test", name + "@example.com", null,
                    Set.of(roles.findByCode("MEMBER").orElseThrow()), encoder.encode("initial-password")));
        });
    }

    @Test
    void loginReturnsConfiguredLifetimesAndOnlyHashesArePersisted() throws Exception {
        JsonNode pair = login();
        assertEquals("Bearer", pair.get("tokenType").asText());
        assertEquals(60, pair.get("expiresIn").asLong());
        assertEquals(7200, pair.get("refreshExpiresIn").asLong());
        assertEquals(now.get().plusSeconds(10800), Instant.parse(pair.get("sessionExpiresAt").asText()));
        String raw = refresh(pair);
        assertEquals(43, raw.length());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM authentication_refresh_tokens WHERE token_hash=?",
                Integer.class, TokenSecrets.hash(raw)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM authentication_refresh_tokens WHERE token_hash=?",
                Integer.class, raw));
        accessible(access(pair), true);
    }

    @Test
    void refreshWorksAfterAccessExpiryAndRotatesBothCredentials() throws Exception {
        JsonNode original = login();
        now.set(now.get().plusSeconds(61));
        accessible(access(original), false);
        JsonNode renewed = rotate(refresh(original));
        assertNotEquals(access(original), access(renewed));
        assertNotEquals(refresh(original), refresh(renewed));
        accessible(access(renewed), true);
        assertEquals(original.get("sessionExpiresAt"), renewed.get("sessionExpiresAt"));
    }

    @Test
    void replayRevokesRefreshFamilyButIssuedAccessExpiresNaturally() throws Exception {
        JsonNode original = login();
        JsonNode renewed = rotate(refresh(original));
        refreshRejected(refresh(original));
        accessible(access(original), true);
        accessible(access(renewed), true);
        refreshRejected(refresh(renewed));
        now.set(now.get().plusSeconds(60));
        accessible(access(original), false);
        accessible(access(renewed), false);
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM authentication_sessions s JOIN authentication_refresh_tokens t ON t.session_id=s.id
                WHERE t.token_hash=? AND s.revoked_at IS NOT NULL
                """, Integer.class, TokenSecrets.hash(refresh(original))));
    }

    @Test
    void refreshExpiresAtItsBoundary() throws Exception {
        JsonNode pair = login();
        now.set(now.get().plusSeconds(7200));
        refreshRejected(refresh(pair));
    }

    @Test
    void rotationCannotExtendAbsoluteSessionAndAccessIsCappedAtDeadline() throws Exception {
        JsonNode pair = login();
        Instant start = now.get();
        now.set(start.plusSeconds(5400));
        JsonNode next = rotate(refresh(pair));
        assertEquals(5400, next.get("refreshExpiresIn").asLong());
        now.set(start.plusSeconds(10790));
        JsonNode last = rotate(refresh(next));
        assertEquals(10, last.get("expiresIn").asLong());
        assertEquals(10, last.get("refreshExpiresIn").asLong());
        now.set(start.plusSeconds(10800));
        accessible(access(last), false);
        refreshRejected(refresh(last));
    }

    @Test
    void logoutRevokesOnlyItsRefreshSessionWhileAccessRemainsValidUntilExpiry() throws Exception {
        JsonNode first = login();
        JsonNode otherSession = login();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + access(first)))
            .andExpect(status().isNoContent());
        accessible(access(first), true);
        refreshRejected(refresh(first));
        accessible(access(otherSession), true);
        rotate(refresh(otherSession));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + access(first)))
            .andExpect(status().isNoContent());
        now.set(now.get().plusSeconds(60));
        accessible(access(first), false);
    }

    @Test
    void refreshTokenCanLogoutAfterAccessExpiryAndUnknownTokensAreNoOps() throws Exception {
        JsonNode pair = login();
        now.set(now.get().plusSeconds(61));
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body(refresh(pair))))
            .andExpect(status().isNoContent());
        refreshRejected(refresh(pair));
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body("unknown-token")))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void logoutAllRevokesOwnRefreshSessionsWithoutRevokingIssuedAccess() throws Exception {
        JsonNode first = login();
        JsonNode second = login();
        User other = uniqueUser();
        JsonNode otherPair = login(other.getUsername(), "initial-password");
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + access(first)))
            .andExpect(status().isNoContent());
        accessible(access(first), true);
        accessible(access(second), true);
        refreshRejected(refresh(first));
        refreshRejected(refresh(second));
        accessible(access(otherPair), true);
        mvc.perform(post("/api/v1/auth/logout-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangesBlockRefreshWhileIssuedAccessExpiresNaturally() throws Exception {
        User user = uniqueUser();
        JsonNode pair = login(user.getUsername(), "initial-password");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            User stored = users.findById(user.getId()).orElseThrow();
            stored.changePasswordHash(encoder.encode("changed-password"));
            users.save(stored);
        });
        accessible(access(pair), true);
        refreshRejected(refresh(pair));
        accessible(access(login(user.getUsername(), "changed-password")), true);
    }

    @Test
    void disabledAccountsCannotRefreshButExistingAccessExpiresNaturally() throws Exception {
        User user = uniqueUser();
        JsonNode pair = login(user.getUsername(), "initial-password");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            User stored = users.findById(user.getId()).orElseThrow();
            stored.setActive(false);
            users.save(stored);
        });
        accessible(access(pair), true);
        refreshRejected(refresh(pair));
    }

    @Test
    void malformedMissingAndUnknownRefreshTokensAreRejectedWithoutLeakingSecrets() throws Exception {
        refreshRejected("unknown-refresh-secret");
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\" \"}")).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRefreshSerializesAndReplayInvalidatesTheFamily() throws Exception {
        JsonNode original = login();
        var barrier = new CyclicBarrier(2);
        List<Object> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                barrier.await();
                try {
                    return (Object) authentication.refresh(refresh(original));
                }
                catch (InvalidRefreshTokenException failure) {
                    return failure;
                }
            })).toList();
            for (var task : tasks)
                outcomes.add(task.get(15, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, outcomes.stream().filter(TokenPair.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(InvalidRefreshTokenException.class::isInstance).count());
        TokenPair winner = outcomes.stream()
            .filter(TokenPair.class::isInstance)
            .map(TokenPair.class::cast)
            .findFirst()
            .orElseThrow();
        accessible(winner.token(), true);
        refreshRejected(winner.refreshToken());
    }

    @Test
    void refreshUsesCurrentRolesRatherThanStaleAccessClaims() throws Exception {
        User user = uniqueUser();
        JsonNode pair = login(user.getUsername(), "initial-password");
        assertEquals(Set.of("MEMBER"), jwt.parse(access(pair)).orElseThrow().roles());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            User stored = users.findById(user.getId()).orElseThrow();
            stored.setRoles(Set.of(roles.findByCode("ADMIN").orElseThrow()));
            users.save(stored);
        });
        JsonNode renewed = rotate(refresh(pair));
        assertEquals(Set.of("ADMIN"), jwt.parse(access(renewed)).orElseThrow().roles());
    }

    @Test
    void cleanupRemovesExpiredFamiliesAndHashesWithoutRemovingLiveSessions() throws Exception {
        JsonNode expired = login();
        UUID id = jdbc.queryForObject("SELECT session_id FROM authentication_refresh_tokens WHERE token_hash=?",
                UUID.class, TokenSecrets.hash(refresh(expired)));
        Instant start = now.get();
        now.set(start.plusSeconds(9000));
        JsonNode live = login();
        now.set(start.plusSeconds(10800));
        cleanup.purge();
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM authentication_sessions WHERE id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM authentication_refresh_tokens WHERE session_id=?",
                Integer.class, id));
        rotate(refresh(live));
    }

    @Test
    void validAccessTokenDoesNotRequireItsRefreshSessionToExist() throws Exception {
        JsonNode pair = login();
        jdbc.update("""
                DELETE FROM authentication_sessions WHERE id=(
                    SELECT session_id FROM authentication_refresh_tokens WHERE token_hash=?)
                """, TokenSecrets.hash(refresh(pair)));
        mvc.perform(get("/actuator/info").header("Authorization", "Bearer " + access(pair))).andExpect(status().isOk());
        accessible(access(pair), true);
        refreshRejected(refresh(pair));
        now.set(now.get().plusSeconds(60));
        accessible(access(pair), false);
    }

}
