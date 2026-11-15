package com.flowops.infra.persistence.entity;

import com.flowops.domain.CompletionRule;
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
 * JPA entity backing the {@link com.flowops.domain.WorkflowStep} aggregate
 * (table {@code workflow_steps}). Pure data carrier — all business behavior
 * lives on the domain aggregate; field mapping is identical to the schema.
 */
@Entity(name = "WorkflowStep")
@Table(name = "workflow_steps")
public class WorkflowStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private WorkflowEntity workflow;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "required_role", nullable = false, length = 64)
    private String requiredRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_rule", nullable = false, length = 8)
    private CompletionRule completionRule = CompletionRule.ANY_OF;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals = 1;

    /** JSONB condition, e.g. {"field":"amount","op":">","value":5000}; null = always applies. */
    @Column(name = "condition")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> condition = new LinkedHashMap<>();

    @Column(name = "sla_minutes")
    private Integer slaMinutes;

    @Column(name = "escalation_role", length = 64)
    private String escalationRole;

    @Column(name = "is_terminal", nullable = false)
    private boolean terminal = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public WorkflowStepEntity() {
        // JPA
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public WorkflowEntity getWorkflow() { return workflow; }
    public void setWorkflow(WorkflowEntity workflow) { this.workflow = workflow; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRequiredRole() { return requiredRole; }
    public void setRequiredRole(String requiredRole) { this.requiredRole = requiredRole; }

    public CompletionRule getCompletionRule() { return completionRule; }
    public void setCompletionRule(CompletionRule completionRule) { this.completionRule = completionRule; }

    public int getRequiredApprovals() { return requiredApprovals; }
    public void setRequiredApprovals(int requiredApprovals) { this.requiredApprovals = requiredApprovals; }

    public Map<String, Object> getCondition() { return condition; }
    public void setCondition(Map<String, Object> condition) { this.condition = condition; }

    public Integer getSlaMinutes() { return slaMinutes; }
    public void setSlaMinutes(Integer slaMinutes) { this.slaMinutes = slaMinutes; }

    public String getEscalationRole() { return escalationRole; }
    public void setEscalationRole(String escalationRole) { this.escalationRole = escalationRole; }

    public boolean isTerminal() { return terminal; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
