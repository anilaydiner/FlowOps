package com.flowops.domain.identity;

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
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code persistence.entity.PasswordResetTokenEntity}; the
 * rehydration constructor and setters exist solely for that mapper.
 */
public class PasswordResetToken {

    private Long id;

    private Long userId;

    /** SHA-256 hex digest of the raw token (the raw value is never stored). */
    private String tokenHash;

    private Instant expiresAt;

    /** Set when the token has been consumed (single-use enforcement). */
    private Instant usedAt;

    private Instant createdAt;

    public PasswordResetToken() {
        // Rehydration only — used by the persistence mapper, not by business code.
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

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
