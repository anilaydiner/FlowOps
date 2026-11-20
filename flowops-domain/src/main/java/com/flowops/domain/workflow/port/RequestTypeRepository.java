package com.flowops.domain.workflow.port;

import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.workflow.RequestType;
import com.flowops.domain.workflow.query.RequestTypeFilter;
import java.util.Optional;

/**
 * Persistence port for the {@link RequestType} aggregate.
 */
public interface RequestTypeRepository {

    RequestType save(RequestType type);

    Optional<RequestType> findById(Long id);

    PageResult<RequestType> findAll(RequestTypeFilter filter, PageQuery query);

    Optional<RequestType> findByCode(String code);

    boolean existsByCode(String code);

    long count();

}
