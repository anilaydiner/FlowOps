package com.flowops.api.dto;

import com.flowops.application.identity.session.TokenPair;
import java.time.Instant;

public record AuthResponse(String token, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn,
        Instant sessionExpiresAt) {
    public static AuthResponse from(TokenPair pair) {
        return new AuthResponse(pair.token(), "Bearer", pair.expiresIn(), pair.refreshToken(), pair.refreshExpiresIn(),
                pair.sessionExpiresAt());
    }

    @Override
    public String toString() {
        return "AuthResponse[redacted]";
    }
}
