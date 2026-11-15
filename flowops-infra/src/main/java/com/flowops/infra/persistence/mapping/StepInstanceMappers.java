package com.flowops.infra.persistence.mapping;

import com.flowops.domain.StepInstance;
import com.flowops.infra.persistence.entity.RequestEntity;
import com.flowops.infra.persistence.entity.StepInstanceEntity;
import com.flowops.infra.persistence.entity.WorkflowStepEntity;

/**
 * Maps the {@link StepInstance} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class StepInstanceMappers {

    private StepInstanceMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static StepInstanceEntity toEntity(StepInstance d) {
        if (d == null) {
            return null;
        }
        StepInstanceEntity e = new StepInstanceEntity();
        e.setId(d.getId());
        e.setRequest(RequestMappers.toEntity(d.getRequest()));
        e.setStep(WorkflowStepMappers.toEntity(d.getStep()));
        e.setPosition(d.getPosition());
        e.setState(d.getState());
        e.setRule(d.getRule());
        e.setRequiredApprovals(d.getRequiredApprovals());
        e.setCondition(d.getCondition());
        e.setSkipReason(d.getSkipReason());
        e.setBlockedReason(d.getBlockedReason());
        e.setSatisfiedBy(d.getSatisfiedBy());
        e.setSatisfiedAt(d.getSatisfiedAt());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static StepInstance fromEntity(StepInstanceEntity e) {
        if (e == null) {
            return null;
        }
        StepInstance d = new StepInstance();
        d.setId(e.getId());
        d.setRequest(RequestMappers.fromEntity(e.getRequest()));
        d.setStep(WorkflowStepMappers.fromEntity(e.getStep()));
        d.setPosition(e.getPosition());
        d.setState(e.getState());
        d.setRule(e.getRule());
        d.setRequiredApprovals(e.getRequiredApprovals());
        d.setCondition(e.getCondition());
        d.setSkipReason(e.getSkipReason());
        d.setBlockedReason(e.getBlockedReason());
        d.setSatisfiedBy(e.getSatisfiedBy());
        d.setSatisfiedAt(e.getSatisfiedAt());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
