package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Simulated email store — records every "sent" email for test verification.
 * A real deployment would swap the gateway; this table stays as an audit trail.
 */
@Entity
@Table(name = "email_log")
public class EmailLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "to_address", nullable = false, length = 256)
    private String toAddress;

    @Column(nullable = false, length = 256)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false, length = 16)
    private String status = "DELIVERED";

    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailLogEntry() {
    }

    public EmailLogEntry(String toAddress, String subject, String body, Long outboxId) {
        this.toAddress = toAddress;
        this.subject = subject;
        this.body = body;
        this.outboxId = outboxId;
        this.createdAt = Instant.now();
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public String getToAddress() { return toAddress; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getStatus() { return status; }
    public Long getOutboxId() { return outboxId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(String status) { this.status = status; }
}
