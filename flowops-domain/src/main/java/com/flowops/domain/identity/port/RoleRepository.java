package com.flowops.domain.identity.port;

import com.flowops.domain.identity.Role;
import com.flowops.domain.identity.query.RoleFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import java.util.Collection;
import java.util.Optional;

/**
 * Persistence port for the {@link Role} aggregate.
 *
 * <p>Implemented by the JPA adapter in {@code persistence}; the domain
 * layer only sees this contract — no Spring Data types.
 */
public interface RoleRepository {

    Role save(Role role);

    Optional<Role> findById(Long id);

    void delete(Role role);

    /** Filtered, paged listing (filter may be null for no filters). */
    PageResult<Role> findAll(RoleFilter filter, PageQuery query);

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Returns true when any of the given role codes carries the named
     * permission (used for coarse permission checks like audit:read:any).
     */
    boolean anyRoleHasPermission(Collection<String> roleCodes, String permissionCode);
}
