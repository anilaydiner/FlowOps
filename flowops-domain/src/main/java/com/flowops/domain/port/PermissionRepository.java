package com.flowops.domain.port;

import com.flowops.domain.Permission;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.query.PermissionFilter;

import java.util.Optional;

/**
 * Persistence port for the {@link Permission} catalog.
 *
 * <p>Implemented by the JPA adapter in {@code infra.persistence}; the domain
 * layer only sees this contract — no Spring Data types.
 */
public interface PermissionRepository {

    Optional<Permission> findById(Long id);

    /** Filtered, paged catalog listing (filter may be null for no filters). */
    PageResult<Permission> findAll(PermissionFilter filter, PageQuery query);

    Optional<Permission> findByCode(String code);

    boolean existsByCode(String code);
}
