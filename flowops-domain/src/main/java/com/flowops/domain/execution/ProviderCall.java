package com.flowops.domain.execution;

import java.time.*;

/**
 * Actual invocation budget. Network adapters must apply clipped connect/read limits to
 * their client.
 */
public record ProviderCall(ProviderContract contract, Instant deadline, Duration timeout) {
    public Duration connectTimeout() {
        return minimum(contract.connectTimeout(), timeout);
    }

    public Duration readTimeout() {
        return minimum(contract.readTimeout(), timeout);
    }

    private static Duration minimum(Duration a, Duration b) {
        return a.compareTo(b) < 0 ? a : b;
    }
}
