package com.flowops.domain.request;

import com.flowops.domain.exception.ConflictException;
import java.time.Instant;

/**
 * A Q&A entry in the clarification thread on a request (PLAN §8, iteration 7).
 * Lightweight: no state machine, just ask → optional answer.
 *
 * <p>
 * Pure domain aggregate — no persistence annotations. The JPA mapping lives in
 * {@code persistence.entity.ClarificationEntity}; the rehydration constructor and setters
 * exist solely for that mapper.
 */
public class Clarification {

    private Long id;

    private Long requestId;

    private Long askedBy;

    private String question;

    private String answer;

    private Long answeredBy;

    private Instant answeredAt;

    private Instant createdAt;

    public Clarification() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public Clarification(Long requestId, Long askedBy, String question) {
        this.requestId = requestId;
        this.askedBy = askedBy;
        this.question = question;
        this.createdAt = Instant.now();
    }

    /** Record an answer (one-time; the entry becomes read-only). */
    public void answer(String answer, Long answeredBy) {
        if (isAnswered()) {
            throw new ConflictException("Clarification already answered");
        }
        this.answer = answer;
        this.answeredBy = answeredBy;
        this.answeredAt = Instant.now();
    }

    public boolean isAnswered() {
        return this.answer != null;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getRequestId() {
        return requestId;
    }

    public Long getAskedBy() {
        return askedBy;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public Long getAnsweredBy() {
        return answeredBy;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) {
        this.id = id;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public void setAskedBy(Long askedBy) {
        this.askedBy = askedBy;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public void setAnsweredBy(Long answeredBy) {
        this.answeredBy = answeredBy;
    }

    public void setAnsweredAt(Instant answeredAt) {
        this.answeredAt = answeredAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
