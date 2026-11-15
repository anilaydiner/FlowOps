package com.flowops.infra.worker;

/**
 * Shared scheduling tuning for the scheduled workers
 * ({@link JobWorker}, {@link OutboxDispatcher}, {@link SlaScanner}).
 *
 * <p>Only infra concerns live here (tick cadence, claim batch size).
 * Business policy — retry backoff, SLA windows — belongs to the domain
 * ({@code ExecutionJob.retryBackoff()}, {@code StepInstance.isExpired()}).
 * Promote to configuration properties if per-environment tuning is ever needed.
 */
public final class WorkerDefaults {

    private WorkerDefaults() {}

    /** Tick interval for JobWorker and OutboxDispatcher (ms). */
    public static final long TICK_DELAY_MS = 2000L;

    /** Tick interval for SlaScanner (ms) — SLA checks run less often. */
    public static final long SLA_TICK_DELAY_MS = 5000L;
}
