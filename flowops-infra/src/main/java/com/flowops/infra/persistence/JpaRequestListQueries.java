package com.flowops.infra.persistence;

import com.flowops.application.model.RequestListItem;
import com.flowops.application.request.port.RequestListQueries;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.query.RequestFilter;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.Objects;

@Repository
public class JpaRequestListQueries implements RequestListQueries {
    private final EntityManager entityManager;

    public JpaRequestListQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public PageResult<RequestListItem> findAll(RequestFilter filter, PageQuery page, Long callerId, boolean canSeeAll) {
        Objects.requireNonNull(callerId, "callerId");
        return RequestListProjection.findPage(entityManager, page,
                RequestSpecifications.from(filter, callerId, canSeeAll));
    }
}
