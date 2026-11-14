package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An internal feed notification visible to the recipient (PLAN §8).
 * Created by the dispatcher when delivering a FEED-channel outbox message.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
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
}
