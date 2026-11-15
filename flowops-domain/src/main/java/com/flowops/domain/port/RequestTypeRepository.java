package com.flowops.domain.port;

import com.flowops.domain.RequestType;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.query.RequestTypeFilter;

import java.util.List;
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
