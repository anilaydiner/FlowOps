package com.flowops.domain;

import com.flowops.domain.exception.InvalidStateTransitionException;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A workflow step materialized for a specific request (PLAN §3).
 * The engine advances a request by satisfying/skipping these in position order.
 */
@Entity
@Table(name = "step_instances")
public class StepInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    private WorkflowStep step;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private StepState state = StepState.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule", nullable = false, length = 8)
    private CompletionRule rule;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals;

    @Column(name = "condition")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private Map<String, Object> condition = new LinkedHashMap<>();

    @Column(name = "skip_reason", length = 200)
    private String skipReason;

    @Column(name = "blocked_reason", length = 200)
    private String blockedReason;

    @Column(name = "satisfied_by")
    private Long satisfiedBy;

    @Column(name = "satisfied_at")
    private Instant satisfiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StepInstance() {
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
}
