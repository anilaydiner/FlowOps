package com.flowops.domain;

import com.flowops.api.dto.RequestTypeFilter;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class RequestTypeSpecifications {

    private RequestTypeSpecifications() { }

    public static Specification<RequestType> from(RequestTypeFilter filter) {
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
            if (filter.departmentCode() != null && !filter.departmentCode().isBlank()) {
                Join<?, ?> dept = root.join("owningDepartment", JoinType.INNER);
                predicates.add(cb.equal(
                        cb.upper(dept.get("code")),
                        filter.departmentCode().strip().toUpperCase()));
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
