package com.flowops.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An ordered approval step within a workflow.
 * The optional JSONB {@code condition} is evaluated against the request
 * payload; when false the step is materialized as SKIPPED (Not Applicable).
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code infra.persistence.entity.WorkflowStepEntity}; the
 * rehydration constructor and setters exist solely for that mapper.
 */
public class WorkflowStep {

    private Long id;

    private Workflow workflow;

    private int position;

    private String code;

    private String name;

    private String requiredRole;

    private CompletionRule completionRule = CompletionRule.ANY_OF;

    private int requiredApprovals = 1;

    /** JSONB condition, e.g. {"field":"amount","op":">","value":5000}; null = always applies. */
    private Map<String, Object> condition = new LinkedHashMap<>();

    private Integer slaMinutes;

    private String escalationRole;

    private boolean terminal = false;

    private Instant createdAt;

    public WorkflowStep() {
        // Rehydration only — used by the persistence mapper, not by business code.
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

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setWorkflow(Workflow workflow) { this.workflow = workflow; }
    public void setPosition(int position) { this.position = position; }
    public void setCode(String code) { this.code = code; }
    public void setName(String name) { this.name = name; }
    public void setRequiredRole(String requiredRole) { this.requiredRole = requiredRole; }
    public void setCompletionRule(CompletionRule completionRule) { this.completionRule = completionRule; }
    public void setRequiredApprovals(int requiredApprovals) { this.requiredApprovals = requiredApprovals; }
    public void setCondition(Map<String, Object> condition) { this.condition = condition; }
    public void setSlaMinutes(Integer slaMinutes) { this.slaMinutes = slaMinutes; }
    public void setEscalationRole(String escalationRole) { this.escalationRole = escalationRole; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
