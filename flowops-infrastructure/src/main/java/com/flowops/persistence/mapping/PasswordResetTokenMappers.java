package com.flowops.persistence.mapping;

import com.flowops.domain.identity.PasswordResetToken;
import com.flowops.persistence.entity.PasswordResetTokenEntity;

/**
 * Maps the {@link PasswordResetToken} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class PasswordResetTokenMappers {

    private PasswordResetTokenMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static PasswordResetTokenEntity toEntity(PasswordResetToken d) {
        if (d == null) {
            return null;
        }
        PasswordResetTokenEntity e = new PasswordResetTokenEntity();
        e.setId(d.getId());
        e.setUserId(d.getUserId());
        e.setTokenHash(d.getTokenHash());
        e.setExpiresAt(d.getExpiresAt());
        e.setUsedAt(d.getUsedAt());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static PasswordResetToken fromEntity(PasswordResetTokenEntity e) {
        if (e == null) {
            return null;
        }
        PasswordResetToken d = new PasswordResetToken();
        d.setId(e.getId());
        d.setUserId(e.getUserId());
        d.setTokenHash(e.getTokenHash());
        d.setExpiresAt(e.getExpiresAt());
        d.setUsedAt(e.getUsedAt());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
