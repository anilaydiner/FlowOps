package com.flowops.infra.persistence;

import com.flowops.domain.Department;

import com.flowops.domain.query.DepartmentFilter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class DepartmentSpecifications {

    private DepartmentSpecifications() { }

    public static Specification<Department> from(DepartmentFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.code() != null && !filter.code().isBlank()) {
                predicates.add(cb.like(
                        cb.lower(root.get("code")),
                        "%" + filter.code().strip().toLowerCase() + "%"));
            }
            if (filter.name() != null && !filter.name().isBlank()) {
                predicates.add(cb.like(
                        cb.lower(root.get("name")),
                        "%" + filter.name().strip().toLowerCase() + "%"));
            }
            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
