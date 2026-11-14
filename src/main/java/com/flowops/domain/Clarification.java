package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A Q&A entry in the clarification thread on a request (PLAN §8, iteration 7).
 * Lightweight: no state machine, just ask → optional answer.
 */
@Entity
@Table(name = "clarifications")
public class Clarification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "asked_by", nullable = false)
    private Long askedBy;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    @Column(name = "answered_by")
    private Long answeredBy;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Clarification() {
    }

    public Clarification(Long requestId, Long askedBy, String question) {
        this.requestId = requestId;
        this.askedBy = askedBy;
        this.question = question;
        this.createdAt = Instant.now();
    }

    /** Record an answer (one-time; the entry becomes read-only). */
    public void answer(String answer, Long answeredBy) {
        this.answer = answer;
        this.answeredBy = answeredBy;
        this.answeredAt = Instant.now();
    }

    public boolean isAnswered() {
        return this.answer != null;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getRequestId() { return requestId; }
    public Long getAskedBy() { return askedBy; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public Long getAnsweredBy() { return answeredBy; }
    public Instant getAnsweredAt() { return answeredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
