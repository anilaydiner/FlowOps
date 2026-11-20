package com.flowops.domain.identity.port;

import com.flowops.domain.identity.Permission;
import com.flowops.domain.identity.query.PermissionFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the {@link Permission} catalog.
 *
 * <p>
 * Implemented by the JPA adapter in {@code persistence}; the domain layer only sees this
 * contract — no Spring Data types.
 */
public interface PermissionRepository {

    Optional<Permission> findById(Long id);

    /** Filtered, paged catalog listing (filter may be null for no filters). */
    PageResult<Permission> findAll(PermissionFilter filter, PageQuery query);

    Optional<Permission> findByCode(String code);

    boolean existsByCode(String code);

    List<Permission> findAll();

}
