package com.flowops.persistence;

import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.ApprovalQueueQueries;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepState;
import com.flowops.persistence.entity.DecisionEntity;
import com.flowops.persistence.entity.RequestEntity;
import com.flowops.persistence.entity.StepInstanceEntity;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

@Repository
public class JpaApprovalQueueQueries implements ApprovalQueueQueries {
    private final EntityManager entityManager;

    public JpaApprovalQueueQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public PageResult<RequestListItem> findPage(PageQuery page, Long callerId, Set<String> roles) {
        Objects.requireNonNull(callerId, "callerId");
        if (roles.isEmpty()) return PageResult.empty(page.page(), page.size());
        return RequestListProjection.findPage(entityManager, page, eligibleFor(callerId, roles));
    }

    private static Specification<RequestEntity> eligibleFor(Long callerId, Set<String> roles) {
        return (request, query, cb) -> {
            var pending = query.subquery(Integer.class);
            var step = pending.from(StepInstanceEntity.class);
            var delegated = pending.subquery(Integer.class);
            var decision = delegated.from(DecisionEntity.class);
            delegated.select(cb.literal(1)).where(
                    cb.equal(decision.get("stepInstance").get("id"), step.get("id")),
                    cb.equal(decision.get("action"), DecisionAction.DELEGATE),
                    cb.equal(decision.get("delegatedTo"), callerId));
            pending.select(cb.literal(1)).where(
                    cb.equal(step.get("request").get("id"), request.get("id")),
                    cb.equal(step.get("state"), StepState.PENDING),
                    cb.or(step.get("step").get("requiredRole").in(roles), cb.exists(delegated)));
            // EXISTS avoids duplicate requests when multiple decisions or steps match.
            return cb.and(cb.equal(request.get("status"), RequestStatus.IN_REVIEW),
                    cb.notEqual(request.get("requester").get("id"), callerId), cb.exists(pending));
        };
    }
}
