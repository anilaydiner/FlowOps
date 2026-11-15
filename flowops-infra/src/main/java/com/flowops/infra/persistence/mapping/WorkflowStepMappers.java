package com.flowops.infra.persistence.mapping;

import com.flowops.domain.WorkflowStep;
import com.flowops.infra.persistence.entity.WorkflowEntity;
import com.flowops.infra.persistence.entity.WorkflowStepEntity;

/**
 * Maps the {@link WorkflowStep} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
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
        WorkflowStep d = new WorkflowStep();
        d.setId(e.getId());
        d.setWorkflow(WorkflowMappers.fromEntity(e.getWorkflow()));
        d.setPosition(e.getPosition());
        d.setCode(e.getCode());
        d.setName(e.getName());
        d.setRequiredRole(e.getRequiredRole());
        d.setCompletionRule(e.getCompletionRule());
        d.setRequiredApprovals(e.getRequiredApprovals());
        d.setCondition(e.getCondition());
        d.setSlaMinutes(e.getSlaMinutes());
        d.setEscalationRole(e.getEscalationRole());
        d.setTerminal(e.isTerminal());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
