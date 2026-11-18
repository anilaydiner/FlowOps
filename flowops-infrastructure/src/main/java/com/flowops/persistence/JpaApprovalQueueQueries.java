package com.flowops.persistence;

import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.ApprovalQueueQueries;
import com.flowops.domain.identity.policy.PermissionScope;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Authority;
import com.flowops.persistence.entity.DecisionEntity;
import com.flowops.persistence.entity.RequestEntity;
import com.flowops.persistence.entity.StepInstanceEntity;
import com.flowops.persistence.entity.StepParticipantEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import java.util.Objects;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** Compiles the domain's immutable authority into predicates BEFORE pagination and counting. */
@Repository
public class JpaApprovalQueueQueries implements ApprovalQueueQueries {
    private final EntityManager entityManager;
    private final com.flowops.application.request.port.RequestExecutionQueries execution;
    private final java.time.Clock clock;

    public JpaApprovalQueueQueries(EntityManager entityManager, java.time.Clock clock,com.flowops.application.request.port.RequestExecutionQueries execution) {
        this.clock = clock;
        this.entityManager = entityManager;this.execution=execution;
    }

    @Override
    public PageResult<RequestListItem> findPage(PageQuery page, Authority actor) {
        Objects.requireNonNull(actor, "authority");
        if (!actor.active() || actor.grants().isEmpty()) return PageResult.empty(page.page(), page.size());
        return RequestListProjection.findPage(entityManager, page, eligibleFor(actor, clock.instant()),execution);
    }

    private static Specification<RequestEntity> eligibleFor(Authority actor, java.time.Instant now) {
        return (request, query, cb) -> {
            var pending = query.subquery(Integer.class);
            var step = pending.from(StepInstanceEntity.class);
            var incoming = pending.subquery(Integer.class);
            var delegation = incoming.from(DecisionEntity.class);
            incoming.select(cb.literal(1)).where(
                    cb.equal(delegation.get("stepInstance").get("id"), step.get("id")),
                    cb.equal(delegation.get("action"), DecisionAction.DELEGATE),
                    cb.equal(delegation.get("delegatedTo"), actor.actorId()));
            var acted = pending.subquery(Integer.class);
            var prior = acted.from(DecisionEntity.class);
            acted.select(cb.literal(1)).where(
                    cb.equal(prior.get("stepInstance").get("id"), step.get("id")),
                    cb.equal(prior.get("actorId"), actor.actorId()),
                    prior.get("action").in(DecisionAction.APPROVE, DecisionAction.DELEGATE));
            var type = request.join("type");
            var department = type.join("owningDepartment", JoinType.LEFT);
            Predicate granted = cb.or(actor.grants().stream().map(grant -> RequestPermissionPredicates.scope(
                    grant.scope(), actor, request, step, type.get("code"), department.get("code"), cb))
                    .toArray(Predicate[]::new));
            Predicate role = actor.roles().isEmpty() ? cb.disjunction()
                    : step.get("requiredRole").in(actor.roles());
            var membership = pending.subquery(Integer.class);
            var assignment = membership.from(StepParticipantEntity.class);
            var replacements = membership.subquery(Integer.class);
            var replacement = replacements.from(StepParticipantEntity.class);
            replacements.select(cb.literal(1)).where(cb.equal(replacement.get("predecessorId"), assignment.get("id")));
            membership.select(cb.literal(1)).where(
                    cb.equal(assignment.get("stepInstanceId"), step.get("id")),
                    cb.equal(assignment.get("userId"), actor.actorId()), cb.not(cb.exists(replacements)));
            Predicate participation = cb.or(
                    cb.and(cb.isTrue(step.get("rosterSnapshotted")), cb.exists(membership)),
                    cb.and(cb.isFalse(step.get("rosterSnapshotted")), cb.or(role, cb.exists(incoming))));
            pending.select(cb.literal(1)).where(
                    cb.equal(step.get("request").get("id"), request.get("id")),
                    cb.equal(step.get("state"), StepState.PENDING),
                    cb.or(cb.isNull(step.get("expiresAt")), cb.greaterThan(step.get("expiresAt"), now)),
                    participation, cb.not(cb.exists(acted)), granted);
            return cb.and(cb.equal(request.get("status"), RequestStatus.IN_REVIEW),
                    cb.notEqual(request.get("requester").get("id"), actor.actorId()), cb.exists(pending));
        };
    }

}
