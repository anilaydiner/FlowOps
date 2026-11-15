package com.flowops.domain;

import com.flowops.domain.exception.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
 */
@Entity
@Table(name = "execution_jobs")
public class ExecutionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 256)
    private String idempotencyKey;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    /** PROVISIONING | PURCHASE_TICKET | ACCOUNTING_EXPORT */
    @Column(nullable = false, length = 64)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status = JobStatus.PLANNED;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /** Default retry budget before a job is dead-lettered. */
    public static final int DEFAULT_MAX_RETRIES = 5;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = DEFAULT_MAX_RETRIES;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Result payload from the action handler (JSONB). */
    @Column(name = "result")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private Map<String, Object> result;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ExecutionJob() {
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
}
