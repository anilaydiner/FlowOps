package com.flowops.application.identity;

import com.flowops.application.notification.EmailComposer;
import com.flowops.application.notification.OutboxService;
import com.flowops.domain.PasswordResetToken;
import com.flowops.domain.User;
import com.flowops.domain.exception.RequestValidationException;
import com.flowops.domain.port.PasswordResetTokenRepository;
import com.flowops.domain.port.UserRepository;
import com.flowops.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

/**
 * Forgot-password / password-reset use cases (PLAN §9).
 *
 * <p><b>No user enumeration:</b> {@link #requestReset(String)} always behaves
 * the same whether or not the email exists — it never throws on an unknown
 * address and the controller returns a uniform {@code 202 Accepted}.
 *
 * <p><b>Token security:</b> the raw token is a 256-bit {@link SecureRandom}
 * value delivered to the user's email; only its SHA-256 hash is persisted.
 * Tokens are single-use and expire (default 30 minutes).
 *
 * <p>The reset link points at the front end:
 * {@code {flowops.fe-base-url}{flowops.password-reset.path}?token=...} — the
 * FE reads the token from the URL and calls {@link #resetPassword}.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Minimum length enforced for a freshly reset password. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final EmailComposer emailComposer;
    private final OutboxService outbox;
    private final PasswordEncoder passwordEncoder;

    private final String feBaseUrl;
    private final String resetPath;
    private final long expireMinutes;

    public PasswordResetService(UserRepository users,
                                PasswordResetTokenRepository tokens,
                                EmailComposer emailComposer,
                                OutboxService outbox,
                                PasswordEncoder passwordEncoder,
                                @Value("${flowops.fe-base-url:http://localhost:5173}") String feBaseUrl,
                                @Value("${flowops.password-reset.path:/reset-password}") String resetPath,
                                @Value("${flowops.password-reset.expire-minutes:30}") long expireMinutes) {
        this.users = users;
        this.tokens = tokens;
        this.emailComposer = emailComposer;
        this.outbox = outbox;
        this.passwordEncoder = passwordEncoder;
        this.feBaseUrl = stripTrailingSlash(feBaseUrl);
        this.resetPath = resetPath;
        this.expireMinutes = expireMinutes;
    }

    /**
     * Issue a reset token for the given email and enqueue the reset email.
     *
     * <p>Always returns normally — for an unknown email it is a silent no-op so
     * callers (and attackers) cannot distinguish "account exists" from "no
     * account". The controller maps every call to {@code 202 Accepted}.
     *
     * @return the issued token, or {@code null} when no (email-bearing) user matched
     */
    @Transactional
    public PasswordResetToken requestReset(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        User user = users.findByEmail(email.strip())
                .orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            // No leak: identical observable behaviour to the success path.
            return null;
        }

        String rawToken = randomToken();
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = Instant.now().plus(expireMinutes, ChronoUnit.MINUTES);

        // A fresh request supersedes any earlier (unused) tokens for this user.
        tokens.deleteByUserId(user.getId());
        PasswordResetToken token = tokens.save(new PasswordResetToken(user.getId(), tokenHash, expiresAt));

        String resetUrl = feBaseUrl + resetPath + "?token=" + rawToken;
        EmailComposer.ComposedEmail composed = emailComposer.passwordReset(user, resetUrl, expiresAt);
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        outbox.passwordResetEmail(user.getId(), tokenHash,
                composed.subject(), composed.text(), composed.html(), correlationId);

        log.info("PASSWORD RESET requested for user {} (token expires {})",
                user.getId(), expiresAt);
        return token;
    }

    /**
     * Validate a reset token and set the account's new password.
     *
     * <p>All failure modes (unknown token, expired, already used) raise the same
     * {@link RequestValidationException} so the client cannot distinguish them.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new RequestValidationException(Map.of("token", "Reset token is required"));
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new RequestValidationException(Map.of("password",
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
        }

        PasswordResetToken token = tokens.findByTokenHash(sha256Hex(rawToken))
                .filter(t -> t.isUsable(Instant.now()))
                .orElseThrow(() -> new RequestValidationException(Map.of("token", "Invalid or expired reset link")));

        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found: " + token.getUserId()));

        user.changePasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);

        token.markUsed();
        tokens.save(token);
        log.info("PASSWORD RESET completed for user {}", user.getId());
    }

    // ── token helpers (pure JDK, no framework) ───────────────────────────

    /** 256-bit cryptographically-random token, base64url-encoded (URL-safe). */
    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the UTF-8 input, as a 64-char lowercase hex string. */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) return "";
        String u = url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
