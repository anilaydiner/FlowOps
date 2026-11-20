package com.flowops.application.request.port;

/**
 * Driven-side port for SLA scanner observability (PLAN §8).
 *
 * <p>
 * The application layer reports SLA events (breach notification sent, request expired);
 * the adapter ({@code com.flowops.observability}) records the matching Micrometer
 * counters. This keeps the use case free of telemetry types.
 */
public interface SlaMetrics {

    /**
     * Record a committed review escalation recipient intent (overdue or blocked) for the
     * given step code.
     */
    void recordBreach(String stepCode);

    /**
     * Record a request expiry (independent business deadline elapsed) for the given step
     * code.
     */
    void recordExpiry(String stepCode);

}
