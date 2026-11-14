package com.flowops.domain;

import com.flowops.api.dto.RequestFilter;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class RequestSpecifications {

    private RequestSpecifications() { }

    /**
     * @param callerId       the authenticated caller's user id
     * @param callerIsAdmin  admins see all requests; everyone else is restricted to their own
     */
    public static Specification<Request> from(RequestFilter filter, Long callerId, boolean callerIsAdmin) {
        boolean hasTypeFilter = filter != null && filter.typeCode() != null && !filter.typeCode().isBlank();
        boolean hasStatusFilter = filter != null && filter.status() != null && !filter.status().isBlank();
        boolean hasMineFilter = filter != null && Boolean.TRUE.equals(filter.mine());
        boolean restrictToCaller = !callerIsAdmin;
        if (!hasTypeFilter && !hasStatusFilter && !hasMineFilter && !restrictToCaller) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasTypeFilter) {
                Join<?, ?> type = root.join("type", JoinType.INNER);
                predicates.add(cb.equal(
                        cb.upper(type.get("code")),
                        filter.typeCode().strip().toUpperCase()));
            }
            if (hasStatusFilter) {
                predicates.add(cb.equal(root.get("status"),
                        RequestStatus.valueOf(filter.status().strip().toUpperCase())));
            }
            if (hasMineFilter || restrictToCaller) {
                predicates.add(cb.equal(root.get("requester").get("id"), callerId));
            }
            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
