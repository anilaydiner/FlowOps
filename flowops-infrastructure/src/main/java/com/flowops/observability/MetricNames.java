package com.flowops.observability;

/**
 * Central registry of all Micrometer metric names used across the application.
 *
 * <p>Convention: {@code flowops.<component>.<event>}. Keeping the names here
 * prevents drift between the writer (service layer) and any dashboards or
 * alerting rules that reference them.
 */
public final class MetricNames {

    private MetricNames() {}

    // ── Request lifecycle (command handlers) ─────────────────────────────
    public static final String REQUEST_SUBMITTED = "flowops.request.submitted";
    public static final String REQUEST_DECIDED   = "flowops.request.decided";
    public static final String REQUEST_APPROVED  = "flowops.request.approved";
    public static final String REQUEST_CLOSED    = "flowops.request.closed";

    // ── Outbox (OutboxDispatcher) ──────────────────────────────────────
    public static final String OUTBOX_PENDING           = "flowops.outbox.pending";
    public static final String OUTBOX_DISPATCH          = "flowops.outbox.dispatch";
    public static final String OUTBOX_MESSAGE_SENT      = "flowops.outbox.message.sent";
    public static final String OUTBOX_MESSAGE_FAILED    = "flowops.outbox.message.failed";
    public static final String OUTBOX_MESSAGE_DEAD_LETTER = "flowops.outbox.message.dead_letter";

    // ── Execution jobs (JobWorker) ─────────────────────────────────────
    public static final String JOB_PENDING     = "flowops.job.pending";
    public static final String JOB_COMPLETED   = "flowops.job.completed";
    public static final String JOB_FAILED      = "flowops.job.failed";
    public static final String JOB_DEAD_LETTER = "flowops.job.dead_letter";

    // ── SLA (SlaScanner) ───────────────────────────────────────────────
    public static final String SLA_BREACH  = "flowops.sla.breach";
    public static final String SLA_EXPIRED = "flowops.sla.expired";
}
