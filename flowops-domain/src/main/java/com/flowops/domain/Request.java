package com.flowops.domain;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.InvalidStateTransitionException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A workflow request. Iteration 3 covers the draft lifecycle
 * (DRAFT → CANCELLED); submit/workflow lands in iteration 4.
 * Optimistic locking (the persistence entity's {@code @Version}) guards
 * concurrent transitions.
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code infra.persistence.entity.RequestEntity}; the rehydration
 * constructor and setters exist solely for that mapper. Business behavior
 * goes through the named methods ({@link #transitionTo}, {@link #replacePayload}, …).
 */
public class Request {

    private Long id;
    private RequestType type;
    private User requester;
    private RequestStatus status = RequestStatus.DRAFT;
    private Map<String, Object> payload = new LinkedHashMap<>();
    private Long version = 0L;
    private String correlationId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant submittedAt;

    public Request() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public static Request newDraft(RequestType type, User requester,
                                   Map<String, Object> payload, String correlationId) {
        Request r = new Request();
        r.type = type;
        r.requester = requester;
        r.status = RequestStatus.DRAFT;
        r.payload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        r.correlationId = correlationId;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        return r;
    }

    /**
     * Allowed lifecycle transitions (PLAN §5).
     * Terminal states (CLOSED, CANCELLED, EXPIRED) have no outgoing edges.
     */
    private static final Map<RequestStatus, Set<RequestStatus>> ALLOWED_TRANSITIONS = Map.of(
            RequestStatus.DRAFT,       Set.of(RequestStatus.SUBMITTED, RequestStatus.CANCELLED),
            RequestStatus.SUBMITTED,  Set.of(RequestStatus.IN_REVIEW, RequestStatus.APPROVED,
                    RequestStatus.CANCELLED, RequestStatus.EXPIRED),
            RequestStatus.IN_REVIEW,  Set.of(RequestStatus.APPROVED, RequestStatus.REJECTED,
                    RequestStatus.CANCELLED, RequestStatus.EXPIRED),
            RequestStatus.APPROVED,   Set.of(RequestStatus.EXECUTED, RequestStatus.CANCELLED),
            RequestStatus.EXECUTED,   Set.of(RequestStatus.CLOSED),
            RequestStatus.REJECTED,   Set.of(RequestStatus.CLOSED));

    /** Draft-only payload edit — the invariant is enforced here, not by callers. */
    public void replacePayload(Map<String, Object> newPayload) {
        if (!isDraft()) {
            throw new ConflictException("Only DRAFT requests can be edited (current: " + status + ")");
        }
        this.payload = newPayload != null ? new LinkedHashMap<>(newPayload) : new LinkedHashMap<>();
        this.updatedAt = Instant.now();
    }

    /** Cancel — legal from every non-terminal state. */
    public void cancel() {
        transitionTo(RequestStatus.CANCELLED);
    }

    /**
     * State-machine transition: enforces the legal lifecycle above and throws
     * {@link InvalidStateTransitionException} on an illegal edge.
     * (Concurrent writers are additionally serialized by the version guard
     * on the persistence entity.)
     */
    public void transitionTo(RequestStatus newStatus) {
        Set<RequestStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(status, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new InvalidStateTransitionException("request", status, newStatus);
        }
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /** DRAFT → SUBMITTED. */
    public void markSubmitted(Instant at) {
        transitionTo(RequestStatus.SUBMITTED);
        this.submittedAt = at;
        this.updatedAt = at;
    }

    public boolean isDraft() {
        return this.status == RequestStatus.DRAFT;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public RequestType getType() { return type; }
    public User getRequester() { return requester; }
    public RequestStatus getStatus() { return status; }
    public Map<String, Object> getPayload() { return payload; }
    public Long getVersion() { return version; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getSubmittedAt() { return submittedAt; }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setType(RequestType type) { this.type = type; }
    public void setRequester(User requester) { this.requester = requester; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public void setVersion(Long version) { this.version = version; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
}
