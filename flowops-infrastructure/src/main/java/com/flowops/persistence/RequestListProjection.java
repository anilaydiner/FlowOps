package com.flowops.persistence;

import com.flowops.application.request.model.RequestListItem;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.persistence.entity.RequestEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CompoundSelection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Shared scalar page/count projection; predicates are applied independently to both
 * queries.
 */
final class RequestListProjection {

    private static final Set<String> SORT_FIELDS = Set.of("id", "status", "createdAt", "updatedAt");

    private RequestListProjection() {
    }

    static PageResult<RequestListItem> findPage(EntityManager entityManager, PageQuery page,
            Specification<RequestEntity> spec, com.flowops.application.request.port.RequestExecutionQueries execution) {
        Pageable pageable = Paging.toPageable(page);
        for (PageQuery.Order order : page.orders()) {
            if (!SORT_FIELDS.contains(order.field())) {
                throw new IllegalArgumentException("Unsupported request sort field: " + order.field());
            }
        }
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(RequestListItem.class);
        var root = query.from(RequestEntity.class);
        query.select(selection(cb, root));
        if (spec != null)
            query.where(spec.toPredicate(root, query, cb));
        query.orderBy(page.orders()
            .stream()
            .map(order -> order.descending() ? cb.desc(root.get(order.field())) : cb.asc(root.get(order.field())))
            .toList());
        var content = entityManager.createQuery(query)
            .setFirstResult(Math.toIntExact(pageable.getOffset()))
            .setMaxResults(pageable.getPageSize())
            .getResultList();

        var countQuery = cb.createQuery(Long.class);
        var countRoot = countQuery.from(RequestEntity.class);
        countQuery.select(cb.count(countRoot));
        if (spec != null)
            countQuery.where(spec.toPredicate(countRoot, countQuery, cb));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        var progress = execution.findByRequestIds(content.stream().map(RequestListItem::id).toList());
        return new PageResult<>(
                content.stream().map(item -> item.withExecutionProgress(progress.get(item.id()))).toList(), page.page(),
                page.size(), total);
    }

    static Optional<RequestListItem> findById(EntityManager entityManager, Long id,
            com.flowops.application.request.port.RequestExecutionQueries execution) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(RequestListItem.class);
        var root = query.from(RequestEntity.class);
        query.select(selection(cb, root)).where(cb.equal(root.get("id"), id));
        return entityManager.createQuery(query)
            .getResultList()
            .stream()
            .findFirst()
            .map(item -> item.withExecutionProgress(execution.findByRequestIds(java.util.List.of(id)).get(id)));
    }

    private static CompoundSelection<RequestListItem> selection(CriteriaBuilder cb, Root<RequestEntity> root) {
        var type = root.join("type", JoinType.INNER);
        var department = type.join("owningDepartment", JoinType.LEFT);
        var requester = root.join("requester", JoinType.INNER);
        return cb.construct(RequestListItem.class, root.get("id"), type.get("code"), type.get("name"),
                department.get("code"), root.get("status"), root.get("payload"), root.get("version"),
                requester.get("id"), requester.get("username"), requester.get("displayName"), root.get("correlationId"),
                root.get("createdAt"), root.get("updatedAt"), root.get("submittedAt"), root.get("executionPlannedAt"),
                root.get("executionStartedAt"), root.get("executionStartUnknown"), root.get("executionStartInferred"),
                root.get("executionPlanId"), root.get("outcome"), root.get("approvedAt"), root.get("rejectedAt"),
                root.get("executedAt"), root.get("cancelledAt"), root.get("expiredAt"), root.get("closedAt"));
    }

}
