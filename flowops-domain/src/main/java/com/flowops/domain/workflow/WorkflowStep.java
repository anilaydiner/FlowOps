package com.flowops.domain.workflow;

import java.time.Instant;
import com.flowops.domain.JsonObjects;
import java.util.Map;

/**
 * An ordered approval step within a workflow. The optional JSONB {@code condition} is
 * evaluated against the request payload; when false the step is materialized as SKIPPED
 * (Not Applicable).
 *
 * <p>
 * Pure domain aggregate — no persistence annotations. The JPA mapping lives in
 * {@code persistence.entity.WorkflowStepEntity}; the restoration factory exists for that
 * mapper.
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

    /**
     * JSONB condition, e.g. {"field":"amount","op":">","value":5000}; null = always
     * applies.
     */
    private Map<String, Object> condition = Map.of();

    private Integer slaMinutes;

    private Integer expiryMinutes;

    private int escalationIntervalMinutes = 60;

    private Integer maxEscalationLevels;

    private String escalationRole;

    private boolean terminal = false;

    private Instant createdAt;

    private WorkflowStep() {
    }

    public WorkflowStep(Workflow workflow, int position, String code, String name, String requiredRole) {
        if (position < 1)
            throw new IllegalArgumentException("Step position must be positive");
        this.workflow = workflow;
        this.position = position;
        this.code = code;
        this.name = name;
        this.requiredRole = requiredRole;
        this.createdAt = Instant.now();
    }

    public void configure(CompletionRule rule, int requiredApprovals, Map<String, Object> condition, Integer slaMinutes,
            String escalationRole, boolean terminal) {
        CompletionRule configuredRule = rule == null ? completionRule : rule;
        if (requiredApprovals < 1 || (configuredRule != CompletionRule.K_OF_N && requiredApprovals != 1)) {
            throw new IllegalArgumentException("Invalid approval threshold for " + configuredRule);
        }
        this.completionRule = configuredRule;
        this.requiredApprovals = requiredApprovals;
        if (condition != null)
            this.condition = JsonObjects.immutableCopy(condition);
        if (slaMinutes != null)
            changeReviewSla(slaMinutes);
        if (escalationRole != null && !escalationRole.isBlank())
            this.escalationRole = escalationRole;
        this.terminal = terminal;
    }

    // ── Getters / Setters ──────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public int getPosition() {
        return position;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getRequiredRole() {
        return requiredRole;
    }

    public CompletionRule getCompletionRule() {
        return completionRule;
    }

    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    public Map<String, Object> getCondition() {
        return condition;
    }

    public int getEscalationIntervalMinutes() {
        return escalationIntervalMinutes;
    }

    public void setEscalationIntervalMinutes(int value) {
        if (value < 1)
            throw new IllegalArgumentException("escalationIntervalMinutes must be positive");
        this.escalationIntervalMinutes = value;
    }

    public Integer getMaxEscalationLevels() {
        return maxEscalationLevels;
    }

    public void setMaxEscalationLevels(Integer value) {
        if (value != null && value < 1)
            throw new IllegalArgumentException("maxEscalationLevels must be positive");
        this.maxEscalationLevels = value;
    }

    public Integer getExpiryMinutes() {
        return expiryMinutes;
    }

    public void setExpiryMinutes(Integer value) {
        if (value != null && value < 1)
            throw new IllegalArgumentException("expiryMinutes must be positive");
        this.expiryMinutes = value;
    }

    public Integer getSlaMinutes() {
        return slaMinutes;
    }

    public void changeReviewSla(Integer minutes) {
        this.slaMinutes = minutes;
    }

    public String getEscalationRole() {
        return escalationRole;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public record Snapshot(Long id, Workflow workflow, int position, String code, String name, String requiredRole,
            CompletionRule rule, int requiredApprovals, Map<String, Object> condition, Integer slaMinutes,
            Integer expiryMinutes, int escalationIntervalMinutes, Integer maxEscalationLevels, String escalationRole,
            boolean terminal, Instant createdAt) {
    }

    /** Restore stored definitions without reinterpreting historical policy. */
    public static WorkflowStep restore(Snapshot state) {
        var step = new WorkflowStep();
        step.id = state.id();
        step.workflow = state.workflow();
        step.position = state.position();
        step.code = state.code();
        step.name = state.name();
        step.requiredRole = state.requiredRole();
        step.completionRule = state.rule();
        step.requiredApprovals = state.requiredApprovals();
        step.condition = state.condition() == null ? null : JsonObjects.immutableCopy(state.condition());
        step.slaMinutes = state.slaMinutes();
        step.expiryMinutes = state.expiryMinutes();
        step.escalationIntervalMinutes = state.escalationIntervalMinutes();
        step.maxEscalationLevels = state.maxEscalationLevels();
        step.escalationRole = state.escalationRole();
        step.terminal = state.terminal();
        step.createdAt = state.createdAt();
        return step;
    }

    public void recordPersistence(Long savedId) {
        if (savedId == null || id != null && !id.equals(savedId)) {
            throw new IllegalArgumentException("Workflow step identity cannot change");
        }
        id = savedId;
    }

}
