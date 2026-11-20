package com.flowops.persistence;

import com.flowops.domain.identity.policy.PermissionScope;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Authority;
import com.flowops.persistence.entity.RequestEntity;
import com.flowops.persistence.entity.StepInstanceEntity;
import jakarta.persistence.criteria.*;

/** One SQL compiler for request permission scopes, applied before pagination/counting. */
final class RequestPermissionPredicates {

    private RequestPermissionPredicates() {
    }

    static Predicate scope(PermissionScope scope, Authority actor, Root<RequestEntity> request,
            Root<StepInstanceEntity> step, Expression<String> typeCode, Expression<String> departmentCode,
            CriteriaBuilder cb) {
        if (scope.type() == null)
            return cb.conjunction();
        return switch (scope.type()) {
            case SELF -> cb.equal(request.get("requester").get("id"), actor.actorId());
            case DEPARTMENT -> same(cb, departmentCode, actor.departmentCode());
            case REQUEST_TYPE -> same(cb, typeCode, scope.value());
            case STEP -> same(cb, step.get("stepCode"), scope.value());
            case THRESHOLD -> scope.maximum() == null ? cb.disjunction() : cb
                .le(cb.function("flowops_payload_amount", Double.class, request.get("payload")), scope.maximum());
        };
    }

    private static Predicate same(CriteriaBuilder cb, Expression<String> expression, String value) {
        return value == null ? cb.disjunction()
                : cb.equal(cb.lower(expression), value.toLowerCase(java.util.Locale.ROOT));
    }

}
