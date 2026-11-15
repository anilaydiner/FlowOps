package com.flowops.domain.port;

import com.flowops.domain.Workflow;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.query.WorkflowFilter;

import java.util.Optional;

/**
 * Persistence port for the {@link Workflow} aggregate.
 *
 * <p>Implemented by the JPA adapter in {@code infra.persistence}; the domain
 * layer only sees this contract — no Spring Data types.
 */
public interface WorkflowRepository {

    Workflow save(Workflow workflow);

    Optional<Workflow> findById(Long id);

    /** Filtered, paged listing (filter may be null for no filters). */
    PageResult<Workflow> findAll(WorkflowFilter filter, PageQuery query);

    Optional<Workflow> findByCode(String code);

    boolean existsByCode(String code);
}
