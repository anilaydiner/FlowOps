package com.flowops.domain.port;

import com.flowops.domain.User;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.query.UserFilter;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the {@link User} aggregate.
 *
 * <p>Implemented by the JPA adapter in {@code infra.persistence}; the domain
 * layer only sees this contract — no Spring Data types.
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(Long id);

    void delete(User user);

    /** Filtered, paged listing (filter may be null for no filters). */
    PageResult<User> findAll(UserFilter filter, PageQuery query);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<User> findByEmail(String email);

    /** All users with the department association eagerly fetched. */
    List<User> findAllWithDepartment();

    Optional<User> findByIdWithDepartment(Long id);

    /** Active users carrying a role, excluding one user (SoD-aware eligibility probe). */
    long countActiveByRoleCode(String roleCode, Long excludeId);

    /** Active users carrying a given role code (for SLA escalation). */
    List<User> findActiveByRoleCode(String roleCode);

    /** Coarse permission check: does the user hold a permission with the given code (via any role)? */
    boolean hasPermission(Long userId, String permissionCode);
}
