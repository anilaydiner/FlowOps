package com.flowops.infra.worker;

import java.time.Duration;

/**
 * Shared tuning constants for the scheduled workers
 * ({@link JobWorker}, {@link OutboxDispatcher}, {@link SlaScanner}).
 *
 * <p>Values were previously duplicated as private constants inside each
 * worker; consolidating them here keeps the fleet consistent. Promote to
 * configuration properties if per-environment tuning is ever needed.
 */
public final class WorkerDefaults {

    private WorkerDefaults() {}

    /** Max rows claimed per worker tick (FOR UPDATE SKIP LOCKED batches). */
    public static final int BATCH_SIZE = 50;

    /** Base retry backoff; actual delay = BASE_BACKOFF × 2^retryCount. */
    public static final Duration BASE_BACKOFF = Duration.ofSeconds(5);

    /** Tick interval for JobWorker and OutboxDispatcher (ms). */
    public static final long TICK_DELAY_MS = 2000L;

    /** Tick interval for SlaScanner (ms) — SLA checks run less often. */
    public static final long SLA_TICK_DELAY_MS = 5000L;

    /** Step expiry = slaMinutes × this multiplier. */
    public static final int SLA_EXPIRY_MULTIPLIER = 2;
}
