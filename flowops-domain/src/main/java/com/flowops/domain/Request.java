package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.InvalidStateTransitionException;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A workflow request. Iteration 3 covers the draft lifecycle
 * (DRAFT → CANCELLED); submit/workflow lands in iteration 4.
 * Optimistic locking via {@code @Version} guards concurrent transitions.
 */
@Entity
@Table(name = "requests")
public class Request {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private RequestType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RequestStatus status = RequestStatus.DRAFT;

    @Column(nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "correlation_id", length = 36)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    protected Request() {
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
     * (Concurrent writers are additionally serialized by the {@code @Version} guard.)
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

    // ── Getters / Setters ──────────────────────────────────────────────

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
}
