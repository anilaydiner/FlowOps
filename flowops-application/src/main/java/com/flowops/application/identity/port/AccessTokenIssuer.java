package com.flowops.application.identity.port;

import com.flowops.domain.identity.User;
import java.time.Instant;
import java.util.UUID;

/** Application-owned contract; signing and JWT formatting belong to infrastructure. */
public interface AccessTokenIssuer {

    AccessToken issue(User user, UUID sessionId, Instant sessionExpiresAt);

    record AccessToken(String value, long expiresIn) {
        @Override
        public String toString() {
            return "AccessToken[redacted]";
        }
    }

}
