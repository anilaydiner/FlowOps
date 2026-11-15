package com.flowops.domain;

import com.flowops.domain.exception.InvalidStateTransitionException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A workflow step materialized for a specific request (PLAN §3).
 * The engine advances a request by satisfying/skipping these in position order.
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code infra.persistence.entity.StepInstanceEntity}; the
 * rehydration constructor and setters exist solely for that mapper.
 */
public class StepInstance {

    private Long id;

    private Request request;

    private WorkflowStep step;

    private int position;

    private StepState state = StepState.PENDING;

    private CompletionRule rule;

    private int requiredApprovals;

    private Map<String, Object> condition = new LinkedHashMap<>();

    private String skipReason;

    private String blockedReason;

    private Long satisfiedBy;

    private Instant satisfiedAt;

    private Instant createdAt;

    public StepInstance() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public StepInstance(Request request, WorkflowStep step) {
        this.request = request;
        this.step = step;
        this.position = step.getPosition();
        this.rule = step.getCompletionRule();
        this.requiredApprovals = step.getRequiredApprovals();
        this.condition = step.getCondition() != null ? new LinkedHashMap<>(step.getCondition()) : new LinkedHashMap<>();
        this.state = StepState.PENDING;
        this.createdAt = Instant.now();
    }

    /** PENDING → SKIPPED (conditional step does not apply). */
    public void skip(String reason) {
        requirePending("skip");
        this.state = StepState.SKIPPED;
        this.skipReason = reason;
    }

    /** PENDING → BLOCKED (no eligible approver for the role). */
    public void block(String reason) {
        requirePending("block");
        this.state = StepState.BLOCKED;
        this.blockedReason = reason;
    }

    /** PENDING → SATISFIED (completion rule met). */
    public void satisfy(Long actorId) {
        requirePending("satisfy");
        this.state = StepState.SATISFIED;
        this.satisfiedBy = actorId;
        this.satisfiedAt = Instant.now();
    }

    /** SATISFIED / SKIPPED / BLOCKED are terminal for a step instance. */
    public boolean isTerminal() {
        return this.state != StepState.PENDING;
    }

    // ── SLA policy (PLAN §8, iteration 7) ──────────────────────────────

    /** Step expiry = slaMinutes × this multiplier. */
    public static final int EXPIRY_MULTIPLIER = 2;

    /** True when the step declares an SLA window. */
    public boolean hasSla() {
        return step.getSlaMinutes() != null;
    }

    /** The moment the SLA window ends (breach point). */
    public Instant slaDeadline() {
        return createdAt.plus(Duration.ofMinutes(step.getSlaMinutes()));
    }

    /** The moment the request expires (2× SLA) if the step stays pending. */
    public Instant expiryDeadline() {
        return createdAt.plus(Duration.ofMinutes((long) step.getSlaMinutes() * EXPIRY_MULTIPLIER));
    }

    /** True when the SLA window has been passed. */
    public boolean isSlaBreached(Instant now) {
        return hasSla() && !now.isBefore(slaDeadline());
    }

    /** True when the expiry point has been passed. */
    public boolean isExpired(Instant now) {
        return hasSla() && now.isAfter(expiryDeadline());
    }

    /** The role escalated on breach (falls back to the step's required role). */
    public String escalationRole() {
        return step.getEscalationRole() != null ? step.getEscalationRole() : step.getRequiredRole();
    }

    private void requirePending(String operation) {
        if (state != StepState.PENDING) {
            throw new InvalidStateTransitionException("step instance", state, operation);
        }
    }

    // ── Getters / Setters ──────────────────────────────────────────

    public Long getId() { return id; }
    public Request getRequest() { return request; }
    public WorkflowStep getStep() { return step; }
    public int getPosition() { return position; }
    public StepState getState() { return state; }
    public CompletionRule getRule() { return rule; }
    public int getRequiredApprovals() { return requiredApprovals; }
    public Map<String, Object> getCondition() { return condition; }
    public String getSkipReason() { return skipReason; }
    public String getBlockedReason() { return blockedReason; }
    public Long getSatisfiedBy() { return satisfiedBy; }
    public Instant getSatisfiedAt() { return satisfiedAt; }
    public Instant getCreatedAt() { return createdAt; }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setRequest(Request request) { this.request = request; }
    public void setStep(WorkflowStep step) { this.step = step; }
    public void setPosition(int position) { this.position = position; }
    public void setState(StepState state) { this.state = state; }
    public void setRule(CompletionRule rule) { this.rule = rule; }
    public void setRequiredApprovals(int requiredApprovals) { this.requiredApprovals = requiredApprovals; }
    public void setCondition(Map<String, Object> condition) { this.condition = condition; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }
    public void setSatisfiedBy(Long satisfiedBy) { this.satisfiedBy = satisfiedBy; }
    public void setSatisfiedAt(Instant satisfiedAt) { this.satisfiedAt = satisfiedAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
