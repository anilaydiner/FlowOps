package com.flowops.persistence.mapping;

import com.flowops.domain.workflow.RequestType;
import com.flowops.persistence.entity.RequestTypeEntity;

/**
 * Maps the {@link RequestType} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class RequestTypeMappers {

    private RequestTypeMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static RequestTypeEntity toEntity(RequestType d) {
        if (d == null) {
            return null;
        }
        RequestTypeEntity e = new RequestTypeEntity();
        e.setId(d.getId());
        e.setCode(d.getCode());
        e.setName(d.getName());
        e.setDescription(d.getDescription());
        e.setOwningDepartment(DepartmentMappers.toEntity(d.getOwningDepartment()));
        e.setDefaultWorkflow(WorkflowMappers.toEntity(d.getDefaultWorkflow()));
        e.setRequiredFields(d.getRequiredFields());
        e.setValidationRules(d.getValidationRules());
        e.setExecutionActions(d.getExecutionActions());
        e.setActive(d.isActive());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static RequestType fromEntity(RequestTypeEntity e) {
        if (e == null) {
            return null;
        }
        RequestType d = new RequestType();
        d.setId(e.getId());
        d.setCode(e.getCode());
        d.setName(e.getName());
        d.setDescription(e.getDescription());
        d.setOwningDepartment(DepartmentMappers.fromEntity(e.getOwningDepartment()));
        d.rehydrateDefaultWorkflow(WorkflowMappers.fromEntity(e.getDefaultWorkflow()));
        d.rehydrateRequiredFields(e.getRequiredFields());
        d.rehydrateValidationRules(e.getValidationRules());
        d.setExecutionActions(e.getExecutionActions());
        d.rehydrateActive(e.isActive());
        d.setCreatedAt(e.getCreatedAt());
        d.setUpdatedAt(e.getUpdatedAt());
        return d;
    }
}
