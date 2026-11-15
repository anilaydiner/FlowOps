package com.flowops.infra.persistence;

import com.flowops.domain.query.UserFilter;
import com.flowops.infra.persistence.entity.UserEntity;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds JPA {@link Specification}s from a {@link UserFilter}.
 * Type-safe — no string concatenation, no SQL injection risk.
 */
public final class UserSpecifications {

    private UserSpecifications() { }

    /**
     * Compose a single {@code AND} specification from all non-null filter fields.
     * Returns {@code null} when the filter is empty (caller should skip the spec).
     */
    public static Specification<UserEntity> from(UserFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.username() != null && !filter.username().isBlank()) {
                predicates.add(cb.like(
                        cb.lower(root.get("username")),
                        "%" + filter.username().strip().toLowerCase() + "%"));
            }
            if (filter.displayName() != null && !filter.displayName().isBlank()) {
                predicates.add(cb.like(
                        cb.lower(root.get("displayName")),
                        "%" + filter.displayName().strip().toLowerCase() + "%"));
            }
            if (filter.email() != null && !filter.email().isBlank()) {
                predicates.add(cb.like(
                        cb.lower(root.get("email")),
                        "%" + filter.email().strip().toLowerCase() + "%"));
            }
            if (filter.departmentCode() != null && !filter.departmentCode().isBlank()) {
                Join<?, ?> dept = root.join("department", JoinType.INNER);
                predicates.add(cb.equal(
                        cb.upper(dept.get("code")),
                        filter.departmentCode().strip().toUpperCase()));
            }
            if (filter.roleCode() != null && !filter.roleCode().isBlank()) {
                // Join the user_roles → roles association and match by code
                Join<?, ?> roles = root.join("roles", JoinType.INNER);
                predicates.add(cb.equal(
                        cb.upper(roles.get("code")),
                        filter.roleCode().strip().toUpperCase()));
            }
            if (filter.active() != null) {
                predicates.add(cb.equal(root.get("active"), filter.active()));
            }
            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
