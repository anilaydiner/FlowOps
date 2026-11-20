package com.flowops.domain.workflow.port;

import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.workflow.Workflow;
import com.flowops.domain.workflow.query.WorkflowFilter;
import java.util.Optional;

/**
 * Persistence port for the {@link Workflow} aggregate.
 *
 * <p>
 * Implemented by the JPA adapter in {@code persistence}; the domain layer only sees this
 * contract — no Spring Data types.
 */
public interface WorkflowRepository {

    Workflow save(Workflow workflow);

    Optional<Workflow> findById(Long id);

    /** Filtered, paged listing (filter may be null for no filters). */
    PageResult<Workflow> findAll(WorkflowFilter filter, PageQuery query);

    Optional<Workflow> findByCode(String code);

    boolean existsByCode(String code);

}
