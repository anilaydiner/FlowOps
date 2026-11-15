package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single-use, expiring password-reset token (PLAN §9).
 *
 * <p>Security model:
 * <ul>
 *   <li>The <b>raw</b> random token is delivered to the user's email (in the
 *       reset link) and is <b>never persisted</b>.</li>
 *   <li>Only the SHA-256 <b>hash</b> of the raw token is stored, so a database
 *       leak does not expose usable reset links.</li>
 *   <li>Each token is valid until {@code expiresAt} and is single-use
 *       ({@link #markUsed()}).</li>
 * </ul>
 *
 * <p>Pure-Java aggregate — no Spring types (enforced by the architecture tests).
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 hex digest of the raw token (the raw value is never stored). */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set when the token has been consumed (single-use enforcement). */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PasswordResetToken() {
        // JPA
    }

    public PasswordResetToken(Long userId, String tokenHash, Instant expiresAt) {
        if (userId == null) throw new IllegalArgumentException("userId is required");
        if (tokenHash == null || tokenHash.isBlank()) throw new IllegalArgumentException("tokenHash is required");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    /** Consume the token (single-use). */
    public void markUsed() {
        this.usedAt = Instant.now();
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Usable iff it has not been consumed and is not yet expired. */
    public boolean isUsable(Instant now) {
        return !isUsed() && !isExpired(now);
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
