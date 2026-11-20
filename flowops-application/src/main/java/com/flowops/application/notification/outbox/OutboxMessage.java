package com.flowops.application.notification.outbox;

import com.flowops.domain.exception.InvalidStateTransitionException;
import java.time.Duration;
import java.time.Instant;

/**
 * A row in the transactional outbox (PLAN §8). Written atomically with business state;
 * dispatched by {@code OutboxDispatcher}.
 *
 * <p>
 * Idempotency: the unique {@code idempotencyKey} guarantees each logical notification is
 * enqueued exactly once, even if the triggering code re-runs.
 *
 * <p>
 * Application delivery record — no persistence annotations. The JPA mapping lives in
 * {@code persistence.entity.OutboxMessageEntity}; the rehydration constructor and setters
 * exist solely for that mapper.
 */
public class OutboxMessage {

    private Long id;

    private String idempotencyKey;

    private String channel;

    private Long recipientId;

    private String payload;

    private OutboxStatus status = OutboxStatus.PENDING;

    private int retryCount = 0;

    /** Default retry budget before a message is dead-lettered. */
    public static final int DEFAULT_MAX_RETRIES = 5;

    /** Idempotency-key namespace for all per-request outbox/job messages. */
    public static final String KEY_PREFIX_REQUEST = "req:";

    /**
     * Key format {@code req:<requestId>:<type>:<recipientId>} (feed + email
     * notifications).
     */
    public static final String KEY_FORMAT_NOTIFICATION = "req:%d:%s:%d";

    /**
     * Step-scoped notification: (requestId, type, stepInstanceId, recipientId). The step
     * instance id makes the key per-step, so an approver eligible at several workflow
     * steps of the same request is notified once per step while a retried dispatch of the
     * same step stays idempotent.
     */
    public static final String KEY_FORMAT_STEP_NOTIFICATION = "req:%d:%s:%d:%d";

    /** Key format {@code req:<requestId>:sla:<recipientId>} (SLA breach alerts). */
    public static final String KEY_FORMAT_SLA_BREACH = "req:%d:sla:%d";

    /** Key format {@code user:<userId>:welcome} (new-user welcome email). */
    public static final String KEY_FORMAT_USER_WELCOME = "user:%d:welcome";

    /**
     * Delivery channel: SMTP email (the dispatcher resolves the recipient's address at
     * delivery time).
     */
    public static final String CHANNEL_EMAIL = "EMAIL";

    /** Delivery channel: in-app feed notification. */
    public static final String CHANNEL_FEED = "FEED";

    private int maxRetries = DEFAULT_MAX_RETRIES;

    private Instant nextRetryAt;

    private String lastError;

    private String correlationId;

    private Instant createdAt;

    private Instant sentAt;

    public OutboxMessage() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public OutboxMessage(String idempotencyKey, String channel, Long recipientId, String payload,
            String correlationId) {
        this.idempotencyKey = idempotencyKey;
        this.channel = channel;
        this.recipientId = recipientId;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
    }

    // ── Getters / Setters ────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getChannel() {
        return channel;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
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

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    /**
     * Test-support setter: force retry eligibility by moving the due time into the past.
     */
    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    /**
     * Test-support setter: pre-age retry progress (e.g. to exercise the dead-letter
     * path).
     */
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    // ── Domain behavior ──────────────────────────────────────────────────
    // State machine: PENDING → SENT
    // └────→ FAILED (retryable) → DEAD_LETTER
    // Dispatchers may only claim PENDING/FAILED (see OutboxMessageRepository.claimBatch).

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    /** Base retry backoff; actual delay = BASE_RETRY_DELAY × 2^retryCount. */
    public static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(5);

    /** Exponential backoff for the next retry attempt (5s, 10s, 20s, …). */
    public Duration retryBackoff() {
        return BASE_RETRY_DELAY.multipliedBy(1L << retryCount);
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
        }
        else {
            this.status = OutboxStatus.FAILED;
        }
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) {
        this.id = id;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public void setRecipientId(Long recipientId) {
        this.recipientId = recipientId;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public void setStatus(OutboxStatus status) {
        this.status = status;
    }

    public void rehydrateRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public void rehydrateNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

}
