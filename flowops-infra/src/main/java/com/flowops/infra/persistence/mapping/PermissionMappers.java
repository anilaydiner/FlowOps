package com.flowops.infra.persistence.mapping;

import com.flowops.domain.Permission;
import com.flowops.infra.persistence.entity.PermissionEntity;

/**
 * Maps the {@link Permission} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 */
public final class PermissionMappers {

    private PermissionMappers() {
    }

    /** Domain → entity. Null-safe. */
    public static PermissionEntity toEntity(Permission d) {
        if (d == null) {
            return null;
        }
        PermissionEntity e = new PermissionEntity();
        e.setId(d.getId());
        e.setCode(d.getCode());
        e.setName(d.getName());
        e.setDescription(d.getDescription());
        e.setScopeType(d.getScopeType());
        e.setScopeValue(d.getScopeValue());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    /** Entity → domain. Null-safe. */
    public static Permission fromEntity(PermissionEntity e) {
        if (e == null) {
            return null;
        }
        Permission d = new Permission();
        d.setId(e.getId());
        d.setCode(e.getCode());
        d.rehydrateName(e.getName());
        d.rehydrateDescription(e.getDescription());
        d.rehydrateScopeType(e.getScopeType());
        d.rehydrateScopeValue(e.getScopeValue());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
