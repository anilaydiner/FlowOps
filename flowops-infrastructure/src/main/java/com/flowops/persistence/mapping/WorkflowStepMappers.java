package com.flowops.persistence.mapping;

import com.flowops.domain.workflow.WorkflowStep;
import com.flowops.persistence.entity.WorkflowStepEntity;

/**
 * Maps the {@link WorkflowStep} aggregate to and from its JPA entity. Stateless — safe to
 * call from any thread.
 *
 * <p>
 * {@code fromEntity} runs inside the persistence adapter (i.e. inside the transaction),
 * so lazy associations are safely resolved here and the resulting domain object needs no
 * live persistence context.
 */
public final class WorkflowStepMappers {

    private WorkflowStepMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static WorkflowStepEntity toEntity(WorkflowStep d) {
        if (d == null) {
            return null;
        }
        WorkflowStepEntity e = new WorkflowStepEntity();
        e.setId(d.getId());
        e.setWorkflow(WorkflowMappers.toEntity(d.getWorkflow()));
        e.setPosition(d.getPosition());
        e.setCode(d.getCode());
        e.setName(d.getName());
        e.setRequiredRole(d.getRequiredRole());
        e.setCompletionRule(d.getCompletionRule());
        e.setRequiredApprovals(d.getRequiredApprovals());
        e.setCondition(d.getCondition());
        e.setSlaMinutes(d.getSlaMinutes());
        e.setEscalationIntervalMinutes(d.getEscalationIntervalMinutes());
        e.setMaxEscalationLevels(d.getMaxEscalationLevels());
        e.setExpiryMinutes(d.getExpiryMinutes());
        e.setEscalationRole(d.getEscalationRole());
        e.setTerminal(d.isTerminal());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static WorkflowStep fromEntity(WorkflowStepEntity e) {
        if (e == null) {
            return null;
        }
        return WorkflowStep
            .restore(new WorkflowStep.Snapshot(e.getId(), WorkflowMappers.fromEntity(e.getWorkflow()), e.getPosition(),
                    e.getCode(), e.getName(), e.getRequiredRole(), e.getCompletionRule(), e.getRequiredApprovals(),
                    e.getCondition(), e.getSlaMinutes(), e.getExpiryMinutes(), e.getEscalationIntervalMinutes(),
                    e.getMaxEscalationLevels(), e.getEscalationRole(), e.isTerminal(), e.getCreatedAt()));
    }

}
