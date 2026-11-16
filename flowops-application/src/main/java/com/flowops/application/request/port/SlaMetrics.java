package com.flowops.application.request.port;

/**
 * Driven-side port for SLA scanner observability (PLAN §8).
 *
 * <p>The application layer reports SLA events (breach notification sent,
 * request expired); the adapter ({@code com.flowops.observability}) records
 * the matching Micrometer counters. This keeps the use case free of
 * telemetry types.
 */
public interface SlaMetrics {

    /** Record an SLA breach escalation notification for the given step code. */
    void recordBreach(String stepCode);

    /** Record a request expiry (SLA window exceeded) for the given step code. */
    void recordExpiry(String stepCode);
}
