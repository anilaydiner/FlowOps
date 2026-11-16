package com.flowops.persistence;

import com.flowops.domain.organization.query.DepartmentFilter;
import com.flowops.persistence.entity.DepartmentEntity;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class DepartmentSpecifications {

    private DepartmentSpecifications() { }

    public static Specification<DepartmentEntity> from(DepartmentFilter filter) {
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
