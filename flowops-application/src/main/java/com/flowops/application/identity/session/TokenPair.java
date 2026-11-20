package com.flowops.application.identity.session;

import java.time.Instant;

public record TokenPair(String token, long expiresIn, String refreshToken, long refreshExpiresIn,
        Instant sessionExpiresAt) {
    @Override
    public String toString() {
        return "TokenPair[redacted]";
    }
}
