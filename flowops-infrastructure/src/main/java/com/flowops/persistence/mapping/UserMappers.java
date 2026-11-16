package com.flowops.persistence.mapping;

import com.flowops.domain.identity.User;
import com.flowops.persistence.entity.UserEntity;
import java.util.LinkedHashSet;

/**
 * Maps the {@link User} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class UserMappers {

    private UserMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static UserEntity toEntity(User d) {
        if (d == null) {
            return null;
        }
        UserEntity e = new UserEntity();
        e.setId(d.getId());
        e.setUsername(d.getUsername());
        e.setDisplayName(d.getDisplayName());
        e.setEmail(d.getEmail());
        e.setDepartment(DepartmentMappers.toEntity(d.getDepartment()));
        if (d.getRoles() != null) {
            e.setRoles(d.getRoles().stream()
                    .map(RoleMappers::toEntity)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
        e.setPasswordHash(d.getPasswordHash());
        e.setActive(d.isActive());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static User fromEntity(UserEntity e) {
        if (e == null) {
            return null;
        }
        User d = new User();
        d.setId(e.getId());
        d.setUsername(e.getUsername());
        d.setDisplayName(e.getDisplayName());
        d.setEmail(e.getEmail());
        d.setDepartment(DepartmentMappers.fromEntity(e.getDepartment()));
        if (e.getRoles() != null) {
            d.rehydrateRoles(e.getRoles().stream()
                    .map(RoleMappers::fromEntity)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
        d.setPasswordHash(e.getPasswordHash());
        d.rehydrateActive(e.isActive());
        d.setCreatedAt(e.getCreatedAt());
        d.setUpdatedAt(e.getUpdatedAt());
        return d;
    }
}
