package com.flowops.domain;

import java.time.Instant;

/**
 * An internal feed notification visible to the recipient (PLAN §8).
 * Created by the dispatcher when delivering a FEED-channel outbox message.
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code infra.persistence.entity.NotificationEntity}; the
 * rehydration constructor and setters exist solely for that mapper.
 */
public class Notification {

    private Long id;

    private Long recipientId;

    private String type;

    private String title;

    private String body;

    private Long requestId;

    private Instant readAt;

    private Instant createdAt;

    public Notification() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public Notification(Long recipientId, String type, String title, String body, Long requestId) {
        this.recipientId = recipientId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.requestId = requestId;
        this.createdAt = Instant.now();
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getRecipientId() { return recipientId; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Long getRequestId() { return requestId; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }

    // ── Behavior ─────────────────────────────────────────────────────────

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead() {
        if (readAt == null) {
            this.readAt = Instant.now();
        }
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setRecipientId(Long recipientId) { this.recipientId = recipientId; }
    public void setType(String type) { this.type = type; }
    public void setTitle(String title) { this.title = title; }
    public void setBody(String body) { this.body = body; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
