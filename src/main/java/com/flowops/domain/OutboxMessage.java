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

import java.time.Instant;

/**
 * A row in the transactional outbox (PLAN §8).
 * Written atomically with business state; dispatched by {@code OutboxDispatcher}.
 *
 * <p>Idempotency: the unique {@code idempotencyKey} guarantees each logical
 * notification is enqueued exactly once, even if the triggering code re-runs.
 */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 256)
    private String idempotencyKey;

    @Column(nullable = false, length = 32)
    private String channel;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /** Default retry budget before a message is dead-lettered. */
    public static final int DEFAULT_MAX_RETRIES = 5;

    /** Idempotency-key namespace for all per-request outbox/job messages. */
    public static final String KEY_PREFIX_REQUEST = "req:";

    /** Key format {@code req:<requestId>:<type>:<recipientId>} (feed + email notifications). */
    public static final String KEY_FORMAT_NOTIFICATION = "req:%d:%s:%d";

    /** Key format {@code req:<requestId>:sla:<recipientId>} (SLA breach alerts). */
    public static final String KEY_FORMAT_SLA_BREACH = "req:%d:sla:%d";

    /** Key format {@code req:<requestId>:<action>} (execution jobs). */
    public static final String KEY_FORMAT_JOB = "req:%d:%s";

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = DEFAULT_MAX_RETRIES;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxMessage() {
    }

    public OutboxMessage(String idempotencyKey, String channel, Long recipientId,
                         String payload, String correlationId) {
        this.idempotencyKey = idempotencyKey;
        this.channel = channel;
        this.recipientId = recipientId;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
    }

    // ── Getters / Setters ────────────────────────────────────────────────

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getChannel() { return channel; }
    public Long getRecipientId() { return recipientId; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }

    /** Test-support setter: force retry eligibility by moving the due time into the past. */
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    /** Test-support setter: pre-age retry progress (e.g. to exercise the dead-letter path). */
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    // ── Domain behavior ──────────────────────────────────────────────────
    // State machine: PENDING → SENT
    //                  └────→ FAILED (retryable) → DEAD_LETTER
    // Dispatchers may only claim PENDING/FAILED (see OutboxMessageRepository.claimBatch).

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    /** PENDING|FAILED → SENT. */
    public void markSent() {
        if (status != OutboxStatus.PENDING && status != OutboxStatus.FAILED) {
            throw new InvalidStateTransitionException("outbox message", status, "send");
        }
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
    }

    /** PENDING|FAILED → FAILED, or → DEAD_LETTER when retries are exhausted. */
    public void markFailed(String error, Instant nextRetryAt) {
        if (status != OutboxStatus.PENDING && status != OutboxStatus.FAILED) {
            throw new InvalidStateTransitionException("outbox message", status, "fail");
        }
        this.retryCount++;
        this.lastError = error;
        this.nextRetryAt = nextRetryAt;
        if (retryCount >= maxRetries) {
            this.status = OutboxStatus.DEAD_LETTER;
        } else {
            this.status = OutboxStatus.FAILED;
        }
    }
}
