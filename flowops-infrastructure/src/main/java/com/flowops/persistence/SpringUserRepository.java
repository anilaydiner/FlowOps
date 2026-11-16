package com.flowops.persistence;

import com.flowops.domain.identity.User;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.identity.query.UserFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.persistence.entity.RoleEntity;
import com.flowops.persistence.entity.UserEntity;
import com.flowops.persistence.mapping.UserMappers;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** JPA adapter for the {@link UserRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringUserRepository implements UserRepository {

    private final UserJpaRepository jpaRepository;
    private final RoleJpaRepository roleJpaRepository;

    public SpringUserRepository(UserJpaRepository jpaRepository,
                                RoleJpaRepository roleJpaRepository) {
        this.jpaRepository = jpaRepository;
        this.roleJpaRepository = roleJpaRepository;
    }

    @Override
    public User save(User user) {
        UserEntity entity = UserMappers.toEntity(user);
        if (user.getRoles() != null) {
            // Resolve each role to the canonical persistence-context instance
            // (findById → em.find, an L1-cache hit when already loaded). The
            // mapper's nested mapping produces fresh RoleEntity instances that
            // would pin the user's roles collection to a stale snapshot: a
            // later merge of the same role in this transaction (e.g. granting
            // a new permission) would not be visible through the user's
            // in-memory collection. Canonical instances keep both views in sync.
            Set<RoleEntity> canonical = user.getRoles().stream()
                    .map(role -> roleJpaRepository.findById(role.getId())
                            .orElseThrow(() -> new IllegalStateException("Unknown role id: " + role.getId())))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            entity.setRoles(canonical);
        }
        UserEntity saved = jpaRepository.saveAndFlush(entity);
        user.setId(saved.getId());
        return user;
    }

    @Override
    public Optional<User> findById(Long id) {
        return jpaRepository.findById(id).map(UserMappers::fromEntity);
    }

    @Override
    public void delete(User user) {
        jpaRepository.deleteById(user.getId());
    }

    @Override
    public PageResult<User> findAll(UserFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<UserEntity> spec = UserSpecifications.from(filter);
        Page<UserEntity> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page.map(UserMappers::fromEntity));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jpaRepository.findByUsername(username).map(UserMappers::fromEntity);
    }

    @Override
    public boolean existsByUsername(String username) {
        return jpaRepository.existsByUsername(username);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(UserMappers::fromEntity);
    }

    @Override
    public List<User> findAllWithDepartment() {
        return jpaRepository.findAllWithDepartment().stream().map(UserMappers::fromEntity).toList();
    }

    @Override
    public Optional<User> findByIdWithDepartment(Long id) {
        return jpaRepository.findByIdWithDepartment(id).map(UserMappers::fromEntity);
    }

    @Override
    public long countActiveByRoleCode(String roleCode, Long excludeId) {
        return jpaRepository.countActiveByRoleCode(roleCode, excludeId);
    }

    @Override
    public List<User> findActiveByRoleCode(String roleCode) {
        return jpaRepository.findActiveByRoleCode(roleCode).stream().map(UserMappers::fromEntity).toList();
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        return jpaRepository.hasPermission(userId, permissionCode);
    }
}
