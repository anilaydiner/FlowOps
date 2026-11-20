package com.flowops.application.notification.model;

import java.time.Instant;

/**
 * Simulated email store — records every "sent" email for test verification. A real
 * deployment would swap the gateway; this table stays as an audit trail.
 *
 * <p>
 * Application delivery audit record — no persistence annotations. The JPA mapping lives
 * in {@code persistence.entity.EmailLogEntryEntity}; the rehydration constructor and
 * setters exist solely for that mapper.
 */
public class EmailLogEntry {

    private Long id;

    private String toAddress;

    private String subject;

    private String body;

    private String status = "DELIVERED";

    private Long outboxId;

    private Instant createdAt;

    public EmailLogEntry() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public EmailLogEntry(String toAddress, String subject, String body, Long outboxId) {
        this.toAddress = toAddress;
        this.subject = subject;
        this.body = body;
        this.outboxId = outboxId;
        this.createdAt = Instant.now();
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getToAddress() {
        return toAddress;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getStatus() {
        return status;
    }

    public Long getOutboxId() {
        return outboxId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) {
        this.id = id;
    }

    public void setToAddress(String toAddress) {
        this.toAddress = toAddress;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public void rehydrateStatus(String status) {
        this.status = status;
    }

    public void setOutboxId(Long outboxId) {
        this.outboxId = outboxId;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
