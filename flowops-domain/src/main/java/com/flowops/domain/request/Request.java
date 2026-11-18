package com.flowops.domain.request;

import com.flowops.domain.JsonObjects;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.InvalidStateTransitionException;
import com.flowops.domain.identity.User;
import com.flowops.domain.workflow.RequestType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A workflow request with a guarded lifecycle.
 * Optimistic locking (the persistence entity's {@code @Version}) guards
 * concurrent transitions.
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code persistence.entity.RequestEntity}; the rehydration
 * factory exists solely for that mapper. Business behavior
 * goes through named methods such as {@link #markSubmitted} and {@link #replacePayload}.
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
    private Instant executionPlannedAt;
    private java.util.UUID executionPlanId;
    private java.util.UUID legacyPlanDispositionId;
    private Instant executionStartedAt;
    private boolean executionStartUnknown;
    private boolean executionStartInferred;
    private RequestOutcome outcome;
    private Instant approvedAt;
    private Instant rejectedAt;
    private Instant executedAt;
    private Instant cancelledAt;
    private Instant expiredAt;
    private Instant closedAt;

    private Request() {
    }

    public static Request newDraft(RequestType type, User requester,
                                   Map<String, Object> payload, String correlationId) {
        Request r = new Request();
        r.type = type;
        r.requester = requester;
        r.status = RequestStatus.DRAFT;
        r.payload = JsonObjects.immutableCopy(payload);
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
        this.payload = JsonObjects.immutableCopy(newPayload);
        this.updatedAt = Instant.now();
    }

    /** Advance the aggregate version even when a partial decision does not change status. */
    public void recordDecision() {
        if (status != RequestStatus.IN_REVIEW) {
            throw new ConflictException("Request is not in review");
        }
        recordActivity();
    }

    /** A child mutation is a versioned mutation of the request aggregate too. */
    public void recordActivity() {
        Instant now = Instant.now();
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1000);
    }

    public java.util.UUID getExecutionPlanId() {return executionPlanId;}
    public java.util.UUID getLegacyPlanDispositionId() {return legacyPlanDispositionId;}
    /** Historical uncertainty remains recorded; a reviewed manifest authorizes only the normal fenced execution protocol. */
    public boolean hasUnresolvedExecutionHistory(){return executionStartUnknown && legacyPlanDispositionId==null;}
    public void adoptLegacyExecutionPlan(java.util.UUID planId,java.util.UUID dispositionId,Instant plannedAt){
        if(status!=RequestStatus.APPROVED || executionPlanId!=null || legacyPlanDispositionId!=null)
            throw new ConflictException("legacy-plan-state","Only an approved request without a frozen plan may adopt reviewed execution work");
        Objects.requireNonNull(dispositionId);
        Objects.requireNonNull(planId);
        Objects.requireNonNull(plannedAt);
        if (executionPlannedAt == null) executionPlannedAt = plannedAt;
        executionPlanId = planId;
        legacyPlanDispositionId = dispositionId;
        if(executionStartedAt==null)executionStartUnknown=true;
        recordActivity();
    }
    public void markExecutionStarted(Instant now) {
        if(status!=RequestStatus.APPROVED || hasUnresolvedExecutionHistory() || executionPlannedAt==null || executionPlanId==null)
            throw new ConflictException("execution-state-unknown","Execution requires an approved request with a recorded plan");
        if(executionStartedAt==null) { executionStartedAt=Objects.requireNonNull(now);recordActivity(); }
    }
    /** Provider evidence can establish a legacy start, without inventing a plan or clearing uncertainty. */
    public void observeReconciledExecutionStart(Instant now) {
        if(status!=RequestStatus.APPROVED) throw new ConflictException("Provider recovery requires APPROVED");
        if(executionStartedAt==null) {
            executionStartedAt=Objects.requireNonNull(now);executionStartInferred=true;recordActivity();
        }
    }
    /** Cancellation is allowed only before the first durable execution-start authorization. */
    public void cancel() {
        cancel(Instant.now());
    }
    public void cancel(Instant at) {
        if(status==RequestStatus.CANCELLED) return;
        transitionTo(RequestStatus.CANCELLED,at);
    }
    private void requireCancellationAllowed() {
        if(executionStartedAt!=null) throw new ConflictException("execution-already-started","Execution has already started; operational recovery is required");
        if(executionStartUnknown) throw new ConflictException("execution-state-unknown","Execution history requires reconciliation before cancellation");
        if(status==RequestStatus.APPROVED && (executionPlannedAt==null || executionPlanId==null))
            throw new ConflictException("execution-plan-unknown","An approved request without a recorded execution plan requires reconciliation");
    }
    public Instant getExecutionPlannedAt() { return executionPlannedAt; }
    public Instant getExecutionStartedAt() { return executionStartedAt; }
    public boolean isExecutionStartUnknown() { return executionStartUnknown; }
    public boolean isExecutionStartInferred() { return executionStartInferred; }

    private void transitionTo(RequestStatus newStatus, Instant at) {
        Objects.requireNonNull(at,"transition time");
        Set<RequestStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(status, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new InvalidStateTransitionException("request", status, newStatus);
        }
        if(newStatus==RequestStatus.CANCELLED) requireCancellationAllowed();
        switch(newStatus) {
            case APPROVED -> approvedAt=at;
            case REJECTED -> {rejectedAt=at;outcome=RequestOutcome.REJECTED;}
            case EXECUTED -> {executedAt=at;outcome=RequestOutcome.EXECUTED;}
            case CANCELLED -> {cancelledAt=at;outcome=RequestOutcome.CANCELLED;}
            case EXPIRED -> {expiredAt=at;outcome=RequestOutcome.EXPIRED;}
            case CLOSED -> {
                // The current state establishes the outcome even when its historical timestamp is unknown.
                var priorOutcome=status==RequestStatus.EXECUTED?RequestOutcome.EXECUTED:RequestOutcome.REJECTED;
                if(outcome!=null && outcome!=priorOutcome)throw new ConflictException("Request outcome conflicts with its closing state");
                outcome=priorOutcome;closedAt=at;
            }
            default -> { }
        }
        this.status = newStatus;
        this.updatedAt = at;
    }

    /** DRAFT → SUBMITTED. */
    public void markSubmitted(Instant at) {
        transitionTo(RequestStatus.SUBMITTED,at);
        this.submittedAt = at;
        this.updatedAt = at;
    }

    public void beginReview(Instant at) {
        transitionTo(RequestStatus.IN_REVIEW, at);
    }

    public void reject(Instant at) {
        transitionTo(RequestStatus.REJECTED, at);
    }

    public void expire(Instant at) {
        transitionTo(RequestStatus.EXPIRED, at);
    }

    public void close(Instant at) {
        transitionTo(RequestStatus.CLOSED, at);
    }

    /** Finalize approval using the completed review and this request's immutable plan. */
    public void approve(ApprovalEvidence.FrozenPlan plan, List<StepInstance> stepsInActivationOrder, List<Decision> votes) {
        if (!Objects.equals(id, plan.requestId()) || executionPlanId != null) {
            throw new ConflictException("Approval requires a new execution plan for this request");
        }
        plan.evidence().verifyReview(this, stepsInActivationOrder, votes);
        transitionTo(RequestStatus.APPROVED, plan.plannedAt());
        executionPlanId = plan.id();
        executionPlannedAt = plan.plannedAt();
    }

    /** Evidence of exact successful coverage of the frozen action set. */
    public record ExecutionCompletion(UUID planId, Long requestId) {
        public ExecutionCompletion {
            Objects.requireNonNull(planId);
            Objects.requireNonNull(requestId);
        }
    }

    public void completeExecution(ExecutionCompletion completion, Instant at) {
        if (executionPlanId == null || executionPlannedAt == null || hasUnresolvedExecutionHistory()
                || !executionPlanId.equals(completion.planId()) || !Objects.equals(id, completion.requestId())) {
            throw new ConflictException("Execution completion requires evidence for this request's frozen plan");
        }
        transitionTo(RequestStatus.EXECUTED, at);
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
    public RequestOutcome getOutcome() {return outcome;}
    public Instant getApprovedAt() {return approvedAt;}
    public Instant getRejectedAt() {return rejectedAt;}
    public Instant getExecutedAt() {return executedAt;}
    public Instant getCancelledAt() {return cancelledAt;}
    public Instant getExpiredAt() {return expiredAt;}
    public Instant getClosedAt() {return closedAt;}

    /** Persisted state; restoration does not replay transitions or change timestamps. */
    public record Snapshot(Long id, RequestType type, User requester, RequestStatus status,
                           Map<String, Object> payload, Long version, String correlationId,
                           Instant createdAt, Instant updatedAt, Instant submittedAt, Instant executionPlannedAt,
                           Instant executionStartedAt, boolean executionStartUnknown, boolean executionStartInferred,java.util.UUID executionPlanId,
                           RequestOutcome outcome,Instant approvedAt,Instant rejectedAt,Instant executedAt,Instant cancelledAt,Instant expiredAt,Instant closedAt,java.util.UUID legacyPlanDispositionId) {
        public Snapshot(Long id,RequestType type,User requester,RequestStatus status,Map<String,Object> payload,Long version,String correlationId,
                        Instant createdAt,Instant updatedAt,Instant submittedAt,Instant executionPlannedAt,Instant executionStartedAt,boolean executionStartUnknown,boolean executionStartInferred,java.util.UUID executionPlanId,
                        RequestOutcome outcome,Instant approvedAt,Instant rejectedAt,Instant executedAt,Instant cancelledAt,Instant expiredAt,Instant closedAt){
            this(id,type,requester,status,payload,version,correlationId,createdAt,updatedAt,submittedAt,executionPlannedAt,executionStartedAt,executionStartUnknown,executionStartInferred,executionPlanId,
                    outcome,approvedAt,rejectedAt,executedAt,cancelledAt,expiredAt,closedAt,null);
        }
        public Snapshot(Long id,RequestType type,User requester,RequestStatus status,Map<String,Object> payload,Long version,String correlationId,
                        Instant createdAt,Instant updatedAt,Instant submittedAt,Instant executionPlannedAt,Instant executionStartedAt,boolean executionStartUnknown,boolean executionStartInferred,java.util.UUID executionPlanId) {
            this(id,type,requester,status,payload,version,correlationId,createdAt,updatedAt,submittedAt,executionPlannedAt,executionStartedAt,executionStartUnknown,executionStartInferred,executionPlanId,
                    null,null,null,null,null,null,null);
        }
        public Snapshot(Long id,RequestType type,User requester,RequestStatus status,Map<String,Object> payload,Long version,String correlationId,
                        Instant createdAt,Instant updatedAt,Instant submittedAt,Instant executionPlannedAt,Instant executionStartedAt,boolean executionStartUnknown,boolean executionStartInferred) {
            this(id,type,requester,status,payload,version,correlationId,createdAt,updatedAt,submittedAt,executionPlannedAt,executionStartedAt,executionStartUnknown,executionStartInferred,null);
        }
        public Snapshot(Long id, RequestType type, User requester, RequestStatus status, Map<String,Object> payload,
                        Long version,String correlationId,Instant createdAt,Instant updatedAt,Instant submittedAt) {
            this(id,type,requester,status,payload,version,correlationId,createdAt,updatedAt,submittedAt,null,null,false,false,null);
        }
    }

    /** Persistence adapter only; business code must use newDraft and lifecycle methods. */
    public static Request restore(Snapshot state) {
        Request r = new Request();
        r.id = state.id();
        r.type = state.type();
        r.requester = state.requester();
        r.status = Objects.requireNonNull(state.status(), "status");
        r.payload = JsonObjects.immutableCopy(state.payload());
        r.version = state.version();
        r.correlationId = state.correlationId();
        r.createdAt = state.createdAt();
        r.updatedAt = state.updatedAt();
        r.submittedAt = state.submittedAt();
        r.executionPlannedAt=state.executionPlannedAt(); r.executionStartedAt=state.executionStartedAt();
        r.executionPlanId=state.executionPlanId();
        r.legacyPlanDispositionId=state.legacyPlanDispositionId();
        r.executionStartUnknown=state.executionStartUnknown(); r.executionStartInferred=state.executionStartInferred();
        r.outcome=state.outcome();r.approvedAt=state.approvedAt();r.rejectedAt=state.rejectedAt();r.executedAt=state.executedAt();
        r.cancelledAt=state.cancelledAt();r.expiredAt=state.expiredAt();r.closedAt=state.closedAt();
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
}
