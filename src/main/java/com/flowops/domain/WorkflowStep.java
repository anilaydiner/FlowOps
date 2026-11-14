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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An ordered approval step within a workflow.
 * The optional JSONB {@code condition} is evaluated against the request
 * payload; when false the step is materialized as SKIPPED (Not Applicable).
 */
@Entity
@Table(name = "workflow_steps")
public class WorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

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
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private Map<String, Object> condition = new LinkedHashMap<>();

    @Column(name = "sla_minutes")
    private Integer slaMinutes;

    @Column(name = "escalation_role", length = 64)
    private String escalationRole;

    @Column(name = "is_terminal", nullable = false)
    private boolean terminal = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowStep() {
    }

    public WorkflowStep(Workflow workflow, int position, String code, String name, String requiredRole) {
        this.workflow = workflow;
        this.position = position;
        this.code = code;
        this.name = name;
        this.requiredRole = requiredRole;
        this.createdAt = Instant.now();
    }

    public void configure(CompletionRule rule, int requiredApprovals, Map<String, Object> condition,
                          Integer slaMinutes, String escalationRole, boolean terminal) {
        if (rule != null) this.completionRule = rule;
        if (requiredApprovals > 0) this.requiredApprovals = requiredApprovals;
        if (condition != null) this.condition = new LinkedHashMap<>(condition);
        if (slaMinutes != null) this.slaMinutes = slaMinutes;
        if (escalationRole != null && !escalationRole.isBlank()) this.escalationRole = escalationRole;
        this.terminal = terminal;
    }

    // ── Getters / Setters ──────────────────────────────────────────

    public Long getId() { return id; }
    public Workflow getWorkflow() { return workflow; }
    public int getPosition() { return position; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getRequiredRole() { return requiredRole; }
    public CompletionRule getCompletionRule() { return completionRule; }
    public int getRequiredApprovals() { return requiredApprovals; }
    public Map<String, Object> getCondition() { return condition; }
    public Integer getSlaMinutes() { return slaMinutes; }
    public String getEscalationRole() { return escalationRole; }
    public boolean isTerminal() { return terminal; }
    public Instant getCreatedAt() { return createdAt; }
}
