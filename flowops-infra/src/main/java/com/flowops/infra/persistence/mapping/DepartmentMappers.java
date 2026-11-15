package com.flowops.infra.persistence.mapping;

import com.flowops.domain.Department;
import com.flowops.infra.persistence.entity.DepartmentEntity;

/**
 * Maps the {@link Department} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 */
public final class DepartmentMappers {

    private DepartmentMappers() {
    }

    /** Domain → entity. Null-safe. */
    public static DepartmentEntity toEntity(Department d) {
        if (d == null) {
            return null;
        }
        DepartmentEntity e = new DepartmentEntity();
        e.setId(d.getId());
        e.setCode(d.getCode());
        e.setName(d.getName());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    /** Entity → domain. Null-safe. */
    public static Department fromEntity(DepartmentEntity e) {
        if (e == null) {
            return null;
        }
        Department d = new Department();
        d.setId(e.getId());
        d.setCode(e.getCode());
        d.setName(e.getName());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
