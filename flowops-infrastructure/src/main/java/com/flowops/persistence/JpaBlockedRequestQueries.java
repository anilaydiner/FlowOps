package com.flowops.persistence;

import com.flowops.application.request.model.BlockedRequestView;
import com.flowops.application.request.model.BlockedRequestView.BlockedStep;
import com.flowops.application.request.port.BlockedRequestQueries;
import com.flowops.domain.paging.*;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Authority;
import com.flowops.persistence.entity.RequestEntity;
import com.flowops.persistence.entity.StepInstanceEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

@Repository
public class JpaBlockedRequestQueries implements BlockedRequestQueries {

    private final EntityManager entities;

    private final com.flowops.application.request.port.RequestExecutionQueries execution;

    public JpaBlockedRequestQueries(EntityManager entities,
            com.flowops.application.request.port.RequestExecutionQueries execution) {
        this.entities = entities;
        this.execution = execution;
    }

    @Override
    public PageResult<BlockedRequestView> findPage(PageQuery page, Filter filter, Authority actor) {
        if (!actor.active() || actor.grants().isEmpty())
            return PageResult.empty(page.page(), page.size());
        Specification<RequestEntity> visible = (request, query, cb) -> {
            var blocked = query.subquery(Integer.class);
            var step = blocked.from(StepInstanceEntity.class);
            var type = request.join("type");
            var department = type.join("owningDepartment", JoinType.LEFT);
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(step.get("request").get("id"), request.get("id")));
            predicates.add(cb.equal(step.get("state"), StepState.BLOCKED));
            if (filter.reason() != null)
                predicates.add(cb.equal(step.get("blockReasonCode"), filter.reason()));
            if (filter.blockedBefore() != null)
                predicates.add(cb.lessThanOrEqualTo(step.get("blockedAt"), filter.blockedBefore()));
            if (filter.ownerId() != null)
                predicates.add(cb.equal(step.get("recoveryOwnerId"), filter.ownerId()));
            if (filter.team() != null)
                predicates.add(cb.equal(step.get("recoveryTeam"), filter.team()));
            predicates.add(cb.or(actor.grants()
                .stream()
                .map(grant -> RequestPermissionPredicates.scope(grant.scope(), actor, request, step, type.get("code"),
                        department.get("code"), cb))
                .toArray(Predicate[]::new)));
            blocked.select(cb.literal(1)).where(predicates.toArray(Predicate[]::new));
            return cb.and(cb.equal(request.get("status"), RequestStatus.IN_REVIEW), cb.exists(blocked));
        };
        var requests = RequestListProjection.findPage(entities, page, visible, execution);
        if (requests.content().isEmpty())
            return new PageResult<>(List.of(), page.page(), page.size(), requests.totalElements());
        var cb = entities.getCriteriaBuilder();
        var query = cb.createQuery(BlockedStep.class);
        var step = query.from(StepInstanceEntity.class);
        query
            .select(cb.construct(BlockedStep.class, step.get("request").get("id"), step.get("id"), step.get("stepCode"),
                    step.get("blockReasonCode"), step.get("blockedReason"), step.get("blockedAt"),
                    step.get("blockEpisode"), step.get("recoveryOwnerId"), step.get("recoveryTeam"),
                    step.get("recoveryOwnerRevision"), step.get("recoveryOwnerUnavailable")))
            .where(cb.equal(step.get("state"), StepState.BLOCKED),
                    step.get("request").get("id").in(requests.content().stream().map(r -> r.id()).toList()));
        var byRequest = entities.createQuery(query)
            .getResultList()
            .stream()
            .collect(Collectors.toMap(BlockedStep::requestId, Function.identity()));
        return requests.map(r -> new BlockedRequestView(r, byRequest.get(r.id())));
    }

}
