package com.flowops.domain.port;

import com.flowops.domain.Department;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.query.DepartmentFilter;

import java.util.Optional;

/**
 * Persistence port for the {@link Department} aggregate.
 *
 * <p>Implemented by the JPA adapter in {@code infra.persistence}; the domain
 * layer only sees this contract — no Spring Data types.
 */
public interface DepartmentRepository {

    /** Filtered, paged listing (filter may be null for no filters). */
    PageResult<Department> findAll(DepartmentFilter filter, PageQuery query);

    Optional<Department> findByCode(String code);
}
