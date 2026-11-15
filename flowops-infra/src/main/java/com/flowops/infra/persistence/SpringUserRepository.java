package com.flowops.infra.persistence;

import com.flowops.domain.User;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.UserRepository;
import com.flowops.domain.query.UserFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JPA adapter for the {@link UserRepository} port. */
@Repository
public class SpringUserRepository implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public SpringUserRepository(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public User save(User user) {
        return jpaRepository.save(user);
    }

    @Override
    public Optional<User> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public void delete(User user) {
        jpaRepository.delete(user);
    }

    @Override
    public PageResult<User> findAll(UserFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<User> spec = UserSpecifications.from(filter);
        Page<User> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jpaRepository.findByUsername(username);
    }

    @Override
    public boolean existsByUsername(String username) {
        return jpaRepository.existsByUsername(username);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email);
    }

    @Override
    public List<User> findAllWithDepartment() {
        return jpaRepository.findAllWithDepartment();
    }

    @Override
    public Optional<User> findByIdWithDepartment(Long id) {
        return jpaRepository.findByIdWithDepartment(id);
    }

    @Override
    public long countActiveByRoleCode(String roleCode, Long excludeId) {
        return jpaRepository.countActiveByRoleCode(roleCode, excludeId);
    }

    @Override
    public List<User> findActiveByRoleCode(String roleCode) {
        return jpaRepository.findActiveByRoleCode(roleCode);
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        return jpaRepository.hasPermission(userId, permissionCode);
    }
}
