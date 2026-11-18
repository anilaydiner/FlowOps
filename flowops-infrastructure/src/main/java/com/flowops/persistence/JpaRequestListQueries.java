package com.flowops.persistence;

import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.RequestListQueries;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.request.query.RequestFilter;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import org.springframework.stereotype.Repository;

@Repository
public class JpaRequestListQueries implements RequestListQueries {
    private final EntityManager entityManager;
    private final com.flowops.application.request.port.RequestExecutionQueries execution;

    public JpaRequestListQueries(EntityManager entityManager,com.flowops.application.request.port.RequestExecutionQueries execution) {
        this.entityManager = entityManager;this.execution=execution;
    }

    @Override
    public PageResult<RequestListItem> findAll(RequestFilter filter, PageQuery page, Long callerId, boolean canSeeAll) {
        Objects.requireNonNull(callerId, "callerId");
        return RequestListProjection.findPage(entityManager, page,
                RequestSpecifications.from(filter, callerId, canSeeAll),execution);
    }
}
