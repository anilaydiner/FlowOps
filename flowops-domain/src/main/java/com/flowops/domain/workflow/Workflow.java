package com.flowops.domain.workflow;

import com.flowops.domain.exception.RequestValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A named, code-identified approval workflow.
 *
 * <p>
 * Pure domain aggregate — no persistence annotations. The JPA mapping lives in
 * {@code persistence.entity.WorkflowEntity}; the rehydration constructor and setters
 * exist solely for that mapper.
 */
public class Workflow {

    private Long id;

    private String code;

    private String name;

    private String description;

    private boolean active = true;

    private Instant createdAt;

    private Instant updatedAt;

    public Workflow() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public Workflow(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateProfile(String name, String description) {
        this.name = name;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public List<WorkflowStep> defineSteps(List<StepSpec> definitions) {
        Set<Integer> positions = new HashSet<>();
        for (StepSpec spec : definitions) {
            if (spec.position() != null && (spec.position() < 1 || !positions.add(spec.position()))) {
                throw invalidStep("position", "Explicit positions must be positive and unique");
            }
        }

        var ordered = definitions.stream()
            .sorted(Comparator.comparingInt(spec -> spec.position() == null ? 0 : spec.position()))
            .toList();
        int nextPosition = 1;
        var steps = new ArrayList<WorkflowStep>(definitions.size());
        for (StepSpec spec : ordered) {
            int position;
            if (spec.position() == null) {
                while (positions.contains(nextPosition))
                    nextPosition++;
                position = nextPosition++;
            }
            else {
                position = spec.position();
            }
            steps.add(defineStep(spec, position));
        }
        return List.copyOf(steps);
    }

    private WorkflowStep defineStep(StepSpec spec, int position) {
        CompletionRule rule = spec.completionRule() == null ? CompletionRule.ANY_OF : spec.completionRule();
        if (rule == CompletionRule.ALL_OF && spec.requiredApprovals() != null) {
            throw invalidStep("requiredApprovals",
                    "ALL_OF uses every snapshotted participant; omit requiredApprovals or use K_OF_N for a numeric quorum");
        }
        int approvals = rule == CompletionRule.ANY_OF || spec.requiredApprovals() == null ? 1
                : spec.requiredApprovals();
        if (approvals < 1) {
            throw invalidStep("requiredApprovals", "requiredApprovals must be >= 1 for rule " + rule);
        }
        requirePositive("expiryMinutes", spec.expiryMinutes());
        requirePositive("escalationIntervalMinutes", spec.escalationIntervalMinutes());
        requirePositive("maxEscalationLevels", spec.maxEscalationLevels());

        var step = new WorkflowStep(this, position, spec.code().strip().toUpperCase(Locale.ROOT), spec.name().strip(),
                spec.requiredRole().strip().toUpperCase(Locale.ROOT));
        step.configure(rule, approvals, spec.condition(), spec.slaMinutes(), spec.escalationRole(),
                Boolean.TRUE.equals(spec.terminal()));
        step.setExpiryMinutes(spec.expiryMinutes());
        if (spec.escalationIntervalMinutes() != null)
            step.setEscalationIntervalMinutes(spec.escalationIntervalMinutes());
        step.setMaxEscalationLevels(spec.maxEscalationLevels());
        return step;
    }

    private static void requirePositive(String field, Integer value) {
        if (value != null && value < 1)
            throw invalidStep(field, "Must be positive");
    }

    private static RequestValidationException invalidStep(String field, String message) {
        return new RequestValidationException(Map.of(field, message));
    }

    // ── Getters / Setters ──────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) {
        this.id = id;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void rehydrateActive(boolean active) {
        this.active = active;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

}
