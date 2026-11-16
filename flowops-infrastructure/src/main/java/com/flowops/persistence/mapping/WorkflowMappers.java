package com.flowops.persistence.mapping;

import com.flowops.domain.workflow.Workflow;
import com.flowops.persistence.entity.WorkflowEntity;

/**
 * Maps the {@link Workflow} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class WorkflowMappers {

    private WorkflowMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static WorkflowEntity toEntity(Workflow d) {
        if (d == null) {
            return null;
        }
        WorkflowEntity e = new WorkflowEntity();
        e.setId(d.getId());
        e.setCode(d.getCode());
        e.setName(d.getName());
        e.setDescription(d.getDescription());
        e.setActive(d.isActive());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static Workflow fromEntity(WorkflowEntity e) {
        if (e == null) {
            return null;
        }
        Workflow d = new Workflow();
        d.setId(e.getId());
        d.setCode(e.getCode());
        d.setName(e.getName());
        d.setDescription(e.getDescription());
        d.rehydrateActive(e.isActive());
        d.setCreatedAt(e.getCreatedAt());
        d.setUpdatedAt(e.getUpdatedAt());
        return d;
    }
}
