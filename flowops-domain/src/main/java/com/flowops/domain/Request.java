package com.flowops.domain;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.InvalidStateTransitionException;

import java.time.Instant;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A workflow request with a guarded lifecycle.
 * Optimistic locking (the persistence entity's {@code @Version}) guards
 * concurrent transitions.
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code infra.persistence.entity.RequestEntity}; the rehydration
 * factory exists solely for that mapper. Business behavior
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

    private Request() {
    }

    public static Request newDraft(RequestType type, User requester,
                                   Map<String, Object> payload, String correlationId) {
        Request r = new Request();
        r.type = type;
        r.requester = requester;
        r.status = RequestStatus.DRAFT;
        r.payload = copyPayload(payload);
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
        this.payload = copyPayload(newPayload);
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

    /** Persisted state; restoration does not replay transitions or change timestamps. */
    public record Snapshot(Long id, RequestType type, User requester, RequestStatus status,
                           Map<String, Object> payload, Long version, String correlationId,
                           Instant createdAt, Instant updatedAt, Instant submittedAt) {
    }

    /** Persistence adapter only; business code must use newDraft and lifecycle methods. */
    public static Request restore(Snapshot state) {
        Request r = new Request();
        r.id = state.id();
        r.type = state.type();
        r.requester = state.requester();
        r.status = Objects.requireNonNull(state.status(), "status");
        r.payload = copyPayload(state.payload());
        r.version = state.version();
        r.correlationId = state.correlationId();
        r.createdAt = state.createdAt();
        r.updatedAt = state.updatedAt();
        r.submittedAt = state.submittedAt();
        return r;
    }

    /** Persistence adapter only: acknowledge the database-generated identity and lock version. */
    public void recordPersistence(Long id, Long version) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        if (this.id != null && !this.id.equals(id)) {
            throw new IllegalArgumentException("Cannot change a persisted request's identity");
        }
        this.id = id;
        this.version = version;
    }

    private static Map<String, Object> copyPayload(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(key, copyJsonValue(value)));
        }
        return Collections.unmodifiableMap(copy);
    }

    // JSON objects and arrays can nest. Preserve nulls, key order and scalar types.
    private static Object copyJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, copyJsonValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(nested -> copy.add(copyJsonValue(nested)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
