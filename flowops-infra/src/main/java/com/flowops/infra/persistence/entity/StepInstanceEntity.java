package com.flowops.infra.persistence.entity;

import com.flowops.domain.CompletionRule;
import com.flowops.domain.StepState;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JPA entity backing the {@link com.flowops.domain.StepInstance} aggregate
 * (table {@code step_instances}). Pure data carrier — all business behavior
 * lives on the domain aggregate; field mapping is identical to the schema.
 */
@Entity(name = "StepInstance")
@Table(name = "step_instances")
public class StepInstanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private RequestEntity request;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    private WorkflowStepEntity step;

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
    @JdbcTypeCode(SqlTypes.JSON)
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

    public StepInstanceEntity() {
        // JPA
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RequestEntity getRequest() { return request; }
    public void setRequest(RequestEntity request) { this.request = request; }

    public WorkflowStepEntity getStep() { return step; }
    public void setStep(WorkflowStepEntity step) { this.step = step; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public StepState getState() { return state; }
    public void setState(StepState state) { this.state = state; }

    public CompletionRule getRule() { return rule; }
    public void setRule(CompletionRule rule) { this.rule = rule; }

    public int getRequiredApprovals() { return requiredApprovals; }
    public void setRequiredApprovals(int requiredApprovals) { this.requiredApprovals = requiredApprovals; }

    public Map<String, Object> getCondition() { return condition; }
    public void setCondition(Map<String, Object> condition) { this.condition = condition; }

    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }

    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }

    public Long getSatisfiedBy() { return satisfiedBy; }
    public void setSatisfiedBy(Long satisfiedBy) { this.satisfiedBy = satisfiedBy; }

    public Instant getSatisfiedAt() { return satisfiedAt; }
    public void setSatisfiedAt(Instant satisfiedAt) { this.satisfiedAt = satisfiedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
