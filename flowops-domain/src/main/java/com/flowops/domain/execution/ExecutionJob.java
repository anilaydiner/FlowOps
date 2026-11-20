package com.flowops.domain.execution;

import com.flowops.domain.exception.InvalidStateTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An idempotent execution action triggered when a request is fully approved (PLAN §8,
 * iteration 7). Processed by {@code JobWorker}.
 *
 * <p>
 * Idempotency: the unique {@code idempotencyKey} guarantees each (request, action) pair
 * is enqueued exactly once.
 *
 * <p>
 * Pure domain aggregate — no persistence annotations. The JPA mapping lives in
 * {@code persistence.entity.ExecutionJobEntity}; the rehydration constructor and setters
 * exist solely for that mapper.
 */
public class ExecutionJob {

    private Long id;

    private String idempotencyKey;

    private Long requestId;

    /** PROVISIONING | PURCHASE_TICKET | ACCOUNTING_EXPORT */
    private String action;

    private JobStatus status = JobStatus.PLANNED;

    private int retryCount = 0;

    /** Default retry budget before a job is dead-lettered. */
    public static final int DEFAULT_MAX_RETRIES = 5;

    private int maxRetries = DEFAULT_MAX_RETRIES;

    private Instant nextRetryAt;

    private String lastError;

    /** Result payload from the action handler (JSONB). */
    private Map<String, Object> result;

    private String correlationId;

    private Instant createdAt;

    private Instant completedAt;

    private Instant startedAt;

    private boolean startInferred;

    private Instant cancelledAt;

    private Long cancelledBy;

    private int attemptCount;

    private java.util.UUID claimToken;

    private Instant leaseExpiresAt;

    private Map<String, Object> inputPayload;

    public ExecutionJob() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public ExecutionJob(String idempotencyKey, Long requestId, String action, String correlationId) {
        this.idempotencyKey = idempotencyKey;
        this.requestId = requestId;
        this.action = action;
        this.correlationId = correlationId;
        this.status = JobStatus.PLANNED;
        this.createdAt = Instant.now();
    }

    /**
     * Reconstitute a positively recorded never-started job with its original identity.
     */
    public static ExecutionJob restoredPlanned(Long id, String key, Long requestId, String action, String correlationId,
            Map<String, Object> input, Instant createdAt, int maxRetries) {
        if (id == null || maxRetries < 1)
            throw new IllegalArgumentException("Original job identity and retry budget are required");
        var job = new ExecutionJob(key, requestId, action, correlationId);
        job.freezeInput(input);
        job.id = id;
        job.createdAt = java.util.Objects.requireNonNull(createdAt);
        job.maxRetries = maxRetries;
        return job;
    }

    // ── Getters / Setters ────────────────────────────────────────────────

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant value) {
        this.startedAt = value;
    }

    public boolean isStartInferred() {
        return startInferred;
    }

    public void setStartInferred(boolean value) {
        this.startInferred = value;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant value) {
        this.cancelledAt = value;
    }

    public Long getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(Long value) {
        this.cancelledBy = value;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int value) {
        this.attemptCount = value;
    }

    public java.util.UUID getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(java.util.UUID value) {
        this.claimToken = value;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant value) {
        this.leaseExpiresAt = value;
    }

    public Map<String, Object> getInputPayload() {
        return inputPayload;
    }

    public void setInputPayload(Map<String, Object> value) {
        this.inputPayload = value;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getRequestId() {
        return requestId;
    }

    public String getAction() {
        return action;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    // ── Domain behavior ──────────────────────────────────────────────────
    // State machine: PLANNED → STARTED → COMPLETED
    // └─────→ FAILED (retryable) → DEAD_LETTER
    // Unstarted jobs may be CANCELLED; uncertain outcomes require reconciliation.

    public boolean canRetry() {
        return (status == JobStatus.PLANNED || status == JobStatus.FAILED) && retryCount < maxRetries;
    }

    /** Base retry backoff; actual delay = BASE_RETRY_DELAY × 2^retryCount. */
    public static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(5);

    /** Exponential backoff for the next retry attempt (5s, 10s, 20s, …). */
    public Duration retryBackoff() {
        return BASE_RETRY_DELAY.multipliedBy(1L << retryCount);
    }

    public boolean isDue(Instant now) {
        return (status == JobStatus.PLANNED || status == JobStatus.FAILED) && canRetry()
                && (nextRetryAt == null || !now.isBefore(nextRetryAt));
    }

    public void freezeInput(Map<String, Object> payload) {
        if (id != null || inputPayload != null)
            throw new IllegalStateException("Execution input is already frozen");
        inputPayload = ActionCommand.freeze(payload);
    }

    public void markStarted(Instant now, java.util.UUID token, Instant leaseExpiry) {
        if (!isDue(now) || inputPayload == null || token == null || !leaseExpiry.isAfter(now))
            throw new InvalidStateTransitionException("execution job", status, "start");
        status = JobStatus.STARTED;
        nextRetryAt = null;
        if (startedAt == null)
            startedAt = now;
        attemptCount = Math.incrementExact(attemptCount);
        claimToken = token;
        leaseExpiresAt = leaseExpiry;
    }

    public boolean owns(java.util.UUID token, Instant now) {
        return status == JobStatus.STARTED && token != null && token.equals(claimToken) && leaseExpiresAt != null
                && now.isBefore(leaseExpiresAt);
    }

    /**
     * A heartbeat extends only the current unexpired lease; it never changes start
     * history.
     */
    public boolean renew(java.util.UUID token, Instant now, Instant requestedExpiry) {
        if (!owns(token, now) || !requestedExpiry.isAfter(leaseExpiresAt))
            return false;
        leaseExpiresAt = requestedExpiry;
        return true;
    }

    public void markCompleted(Map<String, Object> result, Instant now) {
        if (status != JobStatus.STARTED)
            throw new InvalidStateTransitionException("execution job", status, "complete");
        status = JobStatus.COMPLETED;
        this.result = result == null ? null : ActionCommand.freeze(result);
        completedAt = now;
        leaseExpiresAt = null;
    }

    public void markFailed(String error, Instant retryAt) {
        if (status != JobStatus.STARTED)
            throw new InvalidStateTransitionException("execution job", status, "fail");
        retryCount++;
        lastError = error;
        nextRetryAt = retryAt;
        leaseExpiresAt = null;
        status = retryCount >= maxRetries ? JobStatus.DEAD_LETTER : JobStatus.FAILED;
    }

    public void requireReconciliation(String reason) {
        if (status != JobStatus.STARTED && status != JobStatus.FAILED && status != JobStatus.DEAD_LETTER)
            throw new InvalidStateTransitionException("execution job", status, "reconcile");
        status = JobStatus.RECONCILIATION_REQUIRED;
        lastError = reason;
        leaseExpiresAt = null;
    }

    /**
     * Plan disposition holds unresolved work without resetting identity, counters or
     * first-start evidence.
     */
    public void holdForLegacyPlan(String reason, Instant now) {
        if (status == JobStatus.RECONCILIATION_REQUIRED)
            return;
        if (status == JobStatus.COMPLETED || status == JobStatus.CANCELLED
                || (status == JobStatus.STARTED && leaseExpiresAt != null && now.isBefore(leaseExpiresAt)))
            throw new com.flowops.domain.exception.ConflictException("legacy-job-state",
                    "A completed, cancelled or live job cannot be held by plan disposition");
        status = JobStatus.RECONCILIATION_REQUIRED;
        lastError = reason;
        leaseExpiresAt = null;
    }

    /**
     * A provider-fenced observation permits the first known attempt without inventing an
     * earlier start.
     */
    public void authorizeLegacyFirstAttempt() {
        if (status != JobStatus.RECONCILIATION_REQUIRED || startedAt != null || attemptCount != 0 || retryCount != 0
                || inputPayload == null)
            throw new InvalidStateTransitionException("execution job", status, "legacy first attempt");
        status = JobStatus.PLANNED;
        nextRetryAt = null;
    }

    public void completeFromProvider(ProviderObservation observation, Instant now) {
        if (status != JobStatus.RECONCILIATION_REQUIRED
                || observation.outcome() != ProviderObservation.Outcome.SUCCEEDED)
            throw new InvalidStateTransitionException("execution job", status, "provider completion");
        if (startedAt == null) {
            startedAt = now;
            startInferred = true;
        }
        attemptCount = Math.max(1, attemptCount);
        status = JobStatus.COMPLETED;
        result = observation.result();
        completedAt = now;
        nextRetryAt = null;
        leaseExpiresAt = null;
    }

    /**
     * One explicit additional retry budget when exhausted; historical counters never
     * reset.
     */
    public void authorizeRecoveryRetry(Instant now) {
        if (status != JobStatus.RECONCILIATION_REQUIRED || inputPayload == null || startedAt == null
                || attemptCount < 1)
            throw new InvalidStateTransitionException("execution job", status, "recovery retry");
        if (retryCount >= maxRetries)
            maxRetries = Math.incrementExact(retryCount);
        status = JobStatus.FAILED;
        nextRetryAt = now;
        leaseExpiresAt = null;
    }

    public void cancel(Instant now, Long actorId) {
        if (status == JobStatus.CANCELLED)
            return;
        if (status != JobStatus.PLANNED || startedAt != null || attemptCount > 0 || retryCount > 0)
            throw new com.flowops.domain.exception.ConflictException("execution-already-started",
                    "A job has execution history");
        status = JobStatus.CANCELLED;
        cancelledAt = now;
        cancelledBy = actorId;
        nextRetryAt = null;
    }
    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) {
        this.id = id;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public void setResult(Map<String, Object> result) {
        this.result = result;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

}
