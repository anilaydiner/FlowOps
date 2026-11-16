package com.flowops.domain.organization.port;

import com.flowops.domain.organization.Department;
import com.flowops.domain.organization.query.DepartmentFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import java.util.Optional;

/**
 * Persistence port for the {@link Department} aggregate.
 *
 * <p>Implemented by the JPA adapter in {@code persistence}; the domain
 * layer only sees this contract — no Spring Data types.
 */
public interface DepartmentRepository {

    /** Filtered, paged listing (filter may be null for no filters). */
    PageResult<Department> findAll(DepartmentFilter filter, PageQuery query);

    Optional<Department> findByCode(String code);
}
