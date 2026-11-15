package com.flowops;

import com.flowops.application.identity.PasswordResetService;
import com.flowops.domain.PasswordResetToken;
import com.flowops.domain.Role;
import com.flowops.domain.User;
import com.flowops.domain.port.OutboxMessageRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6: Forgot password / password reset (PLAN §9).
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/forgot-password} always returns 202 with an
 *       identical body — account existence is never revealed (no enumeration).</li>
 *   <li>Known email → a reset email is queued in the outbox with a
 *       {@code {fe-base-url}/reset-password?token=...} link.</li>
 *   <li>{@code POST /api/v1/auth/reset-password} with the token succeeds,
 *       the new password works, the old one no longer does, and the token
 *       is single-use.</li>
 *   <li>Invalid / expired / weak-password failures all return a uniform 400.</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
class ForgotPasswordTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private OutboxMessageRepository outbox;
    @Autowired private PasswordEncoder encoder;
    @Autowired private com.flowops.domain.port.PasswordResetTokenRepository tokens;

    private static final String OLD_PASSWORD = "OldPass123!";
    private static final String NEW_PASSWORD = "NewPass456!";

    // ── helpers ────────────────────────────────────────────────────────

    private User userWithEmail(String username, String email) {
        Set<Role> roleSet = Set.of(roles.findByCode("MEMBER")
                .orElseThrow(() -> new IllegalStateException("Role MEMBER not found")));
        return users.save(User.newUser(username, username, email, null, roleSet, encoder.encode(OLD_PASSWORD)));
    }

    /** POST forgot-password; returns the response body JSON string. */
    private String requestReset(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    /** Find the newest outbox message queued for the user's reset and extract the raw token from its body. */
    private String extractResetToken(Long userId) {
        List<com.flowops.domain.OutboxMessage> msgs = outbox.findByKeyPrefix("user:" + userId + ":reset:");
        assertThat(msgs).isNotEmpty();
        String payload = msgs.get(msgs.size() - 1).getPayload();
        assertThat(payload).contains("localhost:5173/reset-password?token=");
        int i = payload.indexOf("?token=");
        int start = i + "?token=".length();
        int end = start;
        // base64url alphabet: letters, digits, '-' and '_'
        while (end < payload.length()
                && (Character.isLetterOrDigit(payload.charAt(end))
                    || payload.charAt(end) == '-' || payload.charAt(end) == '_')) {
            end++;
        }
        return payload.substring(start, end);
    }

    private int resetStatus(String token, String newPassword) throws Exception {
        return mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int loginStatus(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/token")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    // ── forgot-password endpoint ───────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/forgot-password")
    class ForgotPassword {

        @Test
        void knownEmail_returns202AndQueuesResetEmail() throws Exception {
            User u = userWithEmail("reset.alice", "reset.alice@example.com");

            mvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType("application/json")
                            .content("{\"email\":\"reset.alice@example.com\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.message").exists());

            List<com.flowops.domain.OutboxMessage> msgs =
                    outbox.findByKeyPrefix("user:" + u.getId() + ":reset:");
            assertThat(msgs).hasSize(1);
            assertThat(msgs.get(0).getChannel()).isEqualTo("EMAIL");
            assertThat(msgs.get(0).getRecipientId()).isEqualTo(u.getId());
            String payload = msgs.get(0).getPayload();
            assertThat(payload).contains("PASSWORD_RESET");
            assertThat(payload).contains("localhost:5173/reset-password?token=");
            assertThat(payload).doesNotContain("reset.alice@example.com"); // never echo the address in the link

            // Only the SHA-256 hash is persisted, never the raw token.
            String rawToken = extractResetToken(u.getId());
            assertThat(tokens.findByTokenHash(rawToken)).isEmpty();
            assertThat(tokens.findByTokenHash(PasswordResetService.sha256Hex(rawToken))).isPresent();
        }

        @Test
        void unknownEmail_returns202WithIdenticalBody() throws Exception {
            long before = outbox.count();

            String unknown = requestReset("nobody-unknown-" + System.nanoTime() + "@example.com");
            long afterUnknown = outbox.count();

            User u = userWithEmail("reset.bob", "reset.bob@example.com");
            String real = requestReset("reset.bob@example.com");

            assertThat(unknown).isEqualTo(real); // identical body → no enumeration signal

            // The unknown address produced no outbox row; the real user exactly one.
            assertThat(afterUnknown).isEqualTo(before);
            assertThat(outbox.count()).isEqualTo(before + 1);
        }

        @Test
        void blankEmail_returns400() throws Exception {
            mvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType("application/json")
                            .content("{\"email\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void reissueSupersedesPreviousTokens() throws Exception {
            User u = userWithEmail("reset.carol", "reset.carol@example.com");
            requestReset("reset.carol@example.com");
            String firstToken = extractResetToken(u.getId());

            requestReset("reset.carol@example.com");
            String secondToken = extractResetToken(u.getId());

            assertThat(secondToken).isNotEqualTo(firstToken);

            // The first (superseded) token is deleted → invalid.
            assertThat(resetStatus(firstToken, NEW_PASSWORD)).isEqualTo(400);

            // The second one still works.
            assertThat(resetStatus(secondToken, NEW_PASSWORD)).isEqualTo(200);
        }
    }

    // ── reset-password endpoint ────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/reset-password")
    class ResetPassword {

        @Test
        void validToken_newPasswordWorks_oldPasswordFails_tokenSingleUse() throws Exception {
            User u = userWithEmail("reset.dave", "reset.dave@example.com");
            requestReset("reset.dave@example.com");
            String token = extractResetToken(u.getId());

            assertThat(resetStatus(token, NEW_PASSWORD)).isEqualTo(200);

            // New credentials work; old ones no longer do (auth failures → 401).
            assertThat(loginStatus("reset.dave", NEW_PASSWORD)).isEqualTo(200);
            assertThat(loginStatus("reset.dave", OLD_PASSWORD)).isEqualTo(401);

            // Single-use: replaying the same token now fails.
            assertThat(resetStatus(token, "Replay123!x")).isEqualTo(400);

            // And the password is unchanged after the failed replay.
            assertThat(loginStatus("reset.dave", NEW_PASSWORD)).isEqualTo(200);
        }

        @Test
        void invalidToken_returns400() throws Exception {
            userWithEmail("reset.eve", "reset.eve@example.com");
            assertThat(resetStatus("not-a-real-token-123", NEW_PASSWORD)).isEqualTo(400);
        }

        @Test
        void expiredToken_returns400() throws Exception {
            User u = userWithEmail("reset.mallory", "reset.mallory@example.com");
            // Seed an already-expired token directly (raw token "expired-token-xyz").
            String raw = "expired-token-xyz";
            tokens.save(new PasswordResetToken(
                    u.getId(),
                    PasswordResetService.sha256Hex(raw),
                    Instant.now().minusSeconds(60)));

            assertThat(resetStatus(raw, NEW_PASSWORD)).isEqualTo(400);
            // Password must be untouched.
            assertThat(loginStatus("reset.mallory", OLD_PASSWORD)).isEqualTo(200);
        }

        @Test
        void weakPassword_returns400() throws Exception {
            User u = userWithEmail("reset.trent", "reset.trent@example.com");
            requestReset("reset.trent@example.com");
            String token = extractResetToken(u.getId());

            assertThat(resetStatus(token, "short")).isEqualTo(400);
            assertThat(loginStatus("reset.trent", OLD_PASSWORD)).isEqualTo(200); // untouched
        }
    }
}
