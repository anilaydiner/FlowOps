package com.flowops.persistence.mapping;

import com.flowops.domain.identity.Role;
import com.flowops.persistence.entity.RoleEntity;
import java.util.LinkedHashSet;

/**
 * Maps the {@link Role} aggregate to and from its JPA entity. Stateless — safe to call
 * from any thread.
 *
 * <p>
 * {@code fromEntity} runs inside the persistence adapter (i.e. inside the transaction),
 * so lazy associations (notably {@code permissions}) are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class RoleMappers {

    private RoleMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static RoleEntity toEntity(Role d) {
        if (d == null) {
            return null;
        }
        RoleEntity e = new RoleEntity();
        e.setId(d.getId());
        e.setCode(d.getCode());
        e.setName(d.getName());
        e.setDescription(d.getDescription());
        e.setCreatedAt(d.getCreatedAt());
        if (d.getPermissions() != null) {
            e.setPermissions(d.getPermissions()
                .stream()
                .map(PermissionMappers::toEntity)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static Role fromEntity(RoleEntity e) {
        if (e == null) {
            return null;
        }
        Role d = new Role();
        d.setId(e.getId());
        d.setCode(e.getCode());
        d.rehydrateName(e.getName());
        d.rehydrateDescription(e.getDescription());
        d.setCreatedAt(e.getCreatedAt());
        if (e.getPermissions() != null) {
            d.rehydratePermissions(e.getPermissions()
                .stream()
                .map(PermissionMappers::fromEntity)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
        return d;
    }

}
