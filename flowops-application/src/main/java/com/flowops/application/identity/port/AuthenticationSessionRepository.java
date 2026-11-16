package com.flowops.application.identity.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthenticationSessionRepository {
    void create(UUID sessionId, Long userId, String credentialFingerprint, Instant createdAt, Instant expiresAt);
    void addRefreshToken(UUID sessionId, String tokenHash, Instant createdAt, Instant expiresAt);

    /** Lock the session and re-read the token after acquiring the lock. Serializes rotation/logout. */
    Optional<RefreshGrant> lockByRefreshToken(String tokenHash);
    void consume(String tokenHash, Instant now);
    void revoke(UUID sessionId, Instant now);
    void revokeAll(Long userId, Instant now);
    int deleteExpired(Instant now);

    record RefreshGrant(UUID sessionId, Long userId, String credentialFingerprint,
                        Instant sessionExpiresAt, Instant revokedAt, Instant tokenExpiresAt, Instant consumedAt) {}
}
