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
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** Step instances loaded for API response (not persisted; populated in-transaction). */
    @jakarta.persistence.Transient
    private java.util.List<StepInstance> loadedSteps;

    /** Decisions per step instance id, loaded for API response. */
    @jakarta.persistence.Transient
    private java.util.Map<Long, java.util.List<Decision>> loadedDecisions;

    /** Clarifications loaded for API response (iteration 7). */
    @jakarta.persistence.Transient
    private java.util.List<com.flowops.domain.Clarification> loadedClarifications;

    /** Execution jobs loaded for API response (iteration 7). */
    @jakarta.persistence.Transient
    private java.util.List<com.flowops.domain.ExecutionJob> loadedJobs;

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

    /** Draft-only payload edit. Caller must have verified status == DRAFT. */
    public void replacePayload(Map<String, Object> newPayload) {
        this.payload = newPayload != null ? new LinkedHashMap<>(newPayload) : new LinkedHashMap<>();
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = RequestStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    /** State-machine transition (guarded by {@code @Version} optimistic locking). */
    public void transitionTo(RequestStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public void markSubmitted(Instant at) {
        this.status = RequestStatus.SUBMITTED;
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
    public java.util.List<StepInstance> getLoadedSteps() { return loadedSteps; }
    public void setLoadedSteps(java.util.List<StepInstance> steps) { this.loadedSteps = steps; }
    public java.util.Map<Long, java.util.List<Decision>> getLoadedDecisions() { return loadedDecisions; }
    public void setLoadedDecisions(java.util.Map<Long, java.util.List<Decision>> decisions) { this.loadedDecisions = decisions; }
    public java.util.List<com.flowops.domain.Clarification> getLoadedClarifications() { return loadedClarifications; }
    public void setLoadedClarifications(java.util.List<com.flowops.domain.Clarification> c) { this.loadedClarifications = c; }
    public java.util.List<com.flowops.domain.ExecutionJob> getLoadedJobs() { return loadedJobs; }
    public void setLoadedJobs(java.util.List<com.flowops.domain.ExecutionJob> j) { this.loadedJobs = j; }
}
