package com.flowops.persistence.entity;

import com.flowops.domain.execution.ExecutionJob;
import com.flowops.domain.execution.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity backing the {@link com.flowops.domain.execution.ExecutionJob} aggregate
 * (table {@code execution_jobs}). Pure data carrier — all business behavior lives on the
 * domain aggregate; field mapping is identical to the schema.
 */
@Entity(name = "ExecutionJob")
@Table(name = "execution_jobs")
public class ExecutionJobEntity {

    @Column(name = "started_at")
    private Instant startedAt;

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant value) {
        this.startedAt = value;
    }

    @Column(name = "start_inferred")
    private boolean startInferred;

    public boolean isStartInferred() {
        return startInferred;
    }

    public void setStartInferred(boolean value) {
        this.startInferred = value;
    }

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant value) {
        this.cancelledAt = value;
    }

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    public Long getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(Long value) {
        this.cancelledBy = value;
    }

    @Column(name = "attempt_count")
    private int attemptCount;

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int value) {
        this.attemptCount = value;
    }

    @Column(name = "claim_token")
    private java.util.UUID claimToken;

    public java.util.UUID getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(java.util.UUID value) {
        this.claimToken = value;
    }

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant value) {
        this.leaseExpiresAt = value;
    }

    @Column(name = "input_payload")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> inputPayload;

    public Map<String, Object> getInputPayload() {
        return inputPayload;
    }

    public void setInputPayload(Map<String, Object> value) {
        this.inputPayload = value;
    }

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
    @Column(nullable = false, length = 32)
    private JobStatus status = JobStatus.PLANNED;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = ExecutionJob.DEFAULT_MAX_RETRIES;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Result payload from the action handler (JSONB). */
    @Column(name = "result")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> result;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public ExecutionJobEntity() {
        // JPA
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
        this.result = result;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

}
