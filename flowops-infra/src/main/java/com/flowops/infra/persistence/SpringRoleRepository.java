package com.flowops.infra.persistence;

import com.flowops.domain.Role;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.RoleRepository;
import com.flowops.domain.query.RoleFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

/** JPA adapter for the {@link RoleRepository} port. */
@Repository
public class SpringRoleRepository implements RoleRepository {

    private final RoleJpaRepository jpaRepository;

    public SpringRoleRepository(RoleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Role save(Role role) {
        return jpaRepository.save(role);
    }

    @Override
    public Optional<Role> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public void delete(Role role) {
        jpaRepository.delete(role);
    }

    @Override
    public PageResult<Role> findAll(RoleFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<Role> spec = RoleSpecifications.from(filter);
        Page<Role> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page);
    }

    @Override
    public Optional<Role> findByCode(String code) {
        return jpaRepository.findByCode(code);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpaRepository.existsByCode(code);
    }

    @Override
    public boolean anyRoleHasPermission(Collection<String> roleCodes, String permissionCode) {
        return jpaRepository.anyRoleHasPermission(roleCodes, permissionCode);
    }
}
