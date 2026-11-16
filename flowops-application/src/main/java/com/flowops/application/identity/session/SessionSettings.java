package com.flowops.application.identity.session;

import java.time.Duration;
import java.util.Objects;

public record SessionSettings(Duration refreshLifetime, Duration maximumLifetime) {
    public SessionSettings {
        Objects.requireNonNull(refreshLifetime, "refreshLifetime");
        Objects.requireNonNull(maximumLifetime, "maximumLifetime");
        if (refreshLifetime.isNegative() || refreshLifetime.isZero() ||
                maximumLifetime.compareTo(refreshLifetime) < 0 || refreshLifetime.getSeconds() < 1) {
            throw new IllegalArgumentException("Session lifetimes must be positive, with maximum >= refresh lifetime");
        }
    }
}
