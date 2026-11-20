package com.flowops.domain.identity.port;

import com.flowops.domain.identity.PasswordResetToken;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link PasswordResetToken}.
 *
 * <p>
 * Implemented by the JPA adapter in {@code persistence}; the domain layer only sees this
 * contract — no Spring Data types.
 */
public interface PasswordResetTokenRepository {

    PasswordResetToken save(PasswordResetToken token);

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** All tokens for a user (to invalidate superseded ones on a new request). */
    List<PasswordResetToken> findAllByUserId(Long userId);

    /** Remove all tokens for a user (e.g. after a password change). */
    void deleteByUserId(Long userId);

}
