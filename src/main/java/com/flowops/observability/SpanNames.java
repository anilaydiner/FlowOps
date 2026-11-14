package com.flowops.observability;

/**
 * Central registry of all OpenTelemetry span names used across the application.
 *
 * <p>Convention: {@code <component>.<action>} (e.g. {@code request.submit}).
 * Dashboards and trace views should reference these constants instead of
 * hardcoding the strings.
 */
public final class SpanNames {

    private SpanNames() {}

    // ── Request lifecycle (RequestService) ─────────────────────────────
    public static final String REQUEST_SUBMIT = "request.submit";
    public static final String REQUEST_DECIDE = "request.decide";
    public static final String REQUEST_CLOSE  = "request.close";

    // ── Workers (infra/worker) ─────────────────────────────────────────
    public static final String JOB_EXECUTE   = "job.execute";
    public static final String OUTBOX_DELIVER = "outbox.deliver";
    public static final String SLA_SCAN      = "sla.scan";
}
