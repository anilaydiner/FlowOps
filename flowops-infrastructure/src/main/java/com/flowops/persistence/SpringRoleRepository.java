package com.flowops.persistence;

import com.flowops.domain.identity.Role;
import com.flowops.domain.identity.port.RoleRepository;
import com.flowops.domain.identity.query.RoleFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.persistence.entity.RoleEntity;
import com.flowops.persistence.mapping.RoleMappers;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** JPA adapter for the {@link RoleRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringRoleRepository implements RoleRepository {

    private final RoleJpaRepository jpaRepository;

    public SpringRoleRepository(RoleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Role save(Role role) {
        RoleEntity saved = jpaRepository.saveAndFlush(RoleMappers.toEntity(role));
        role.setId(saved.getId());
        return role;
    }

    @Override
    public Optional<Role> findById(Long id) {
        return jpaRepository.findById(id).map(RoleMappers::fromEntity);
    }

    @Override
    public void delete(Role role) {
        jpaRepository.deleteById(role.getId());
    }

    @Override
    public PageResult<Role> findAll(RoleFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<RoleEntity> spec = RoleSpecifications.from(filter);
        Page<RoleEntity> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page.map(RoleMappers::fromEntity));
    }

    @Override
    public Optional<Role> findByCode(String code) {
        return jpaRepository.findByCode(code).map(RoleMappers::fromEntity);
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
