package com.flowops.domain;

import com.flowops.domain.exception.InvalidStateTransitionException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An idempotent execution action triggered when a request is fully approved
 * (PLAN §8, iteration 7). Processed by {@code JobWorker}.
 *
 * <p>Idempotency: the unique {@code idempotencyKey} guarantees each
 * (request, action) pair is enqueued exactly once.
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code infra.persistence.entity.ExecutionJobEntity}; the
 * rehydration constructor and setters exist solely for that mapper.
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

    // ── Getters / Setters ────────────────────────────────────────────────

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getRequestId() { return requestId; }
    public String getAction() { return action; }
    public JobStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
    public Map<String, Object> getResult() { return result; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    // ── Domain behavior ──────────────────────────────────────────────────
    // State machine: PLANNED → STARTED → COMPLETED
    //                        └─────→ FAILED (retryable) → DEAD_LETTER
    // Workers may only claim PLANNED/FAILED (see ExecutionJobRepository.claimBatch).

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    /** Base retry backoff; actual delay = BASE_RETRY_DELAY × 2^retryCount. */
    public static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(5);

    /** Exponential backoff for the next retry attempt (5s, 10s, 20s, …). */
    public Duration retryBackoff() {
        return BASE_RETRY_DELAY.multipliedBy(1L << retryCount);
    }

    /** PLANNED|FAILED → STARTED (worker claimed the job). */
    public void markStarted() {
        if (status != JobStatus.PLANNED && status != JobStatus.FAILED) {
            throw new InvalidStateTransitionException("execution job", status, "start");
        }
        this.status = JobStatus.STARTED;
    }

    /** PLANNED|STARTED → COMPLETED. */
    public void markCompleted(Map<String, Object> result) {
        if (status != JobStatus.PLANNED && status != JobStatus.STARTED) {
            throw new InvalidStateTransitionException("execution job", status, "complete");
        }
        this.status = JobStatus.COMPLETED;
        this.result = result != null ? new LinkedHashMap<>(result) : null;
        this.completedAt = Instant.now();
    }

    /** PLANNED|STARTED → FAILED, or → DEAD_LETTER when retries are exhausted. */
    public void markFailed(String error, Instant nextRetryAt) {
        if (status != JobStatus.PLANNED && status != JobStatus.STARTED) {
            throw new InvalidStateTransitionException("execution job", status, "fail");
        }
        this.retryCount++;
        this.lastError = error;
        this.nextRetryAt = nextRetryAt;
        if (retryCount >= maxRetries) {
            this.status = JobStatus.DEAD_LETTER;
        } else {
            this.status = JobStatus.FAILED;
        }
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public void setAction(String action) { this.action = action; }
    public void setStatus(JobStatus status) { this.status = status; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public void setResult(Map<String, Object> result) { this.result = result; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
