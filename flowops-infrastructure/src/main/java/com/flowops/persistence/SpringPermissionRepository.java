package com.flowops.persistence;

import com.flowops.domain.identity.Permission;
import com.flowops.domain.identity.port.PermissionRepository;
import com.flowops.domain.identity.query.PermissionFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.persistence.entity.PermissionEntity;
import com.flowops.persistence.mapping.PermissionMappers;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** JPA adapter for the {@link PermissionRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringPermissionRepository implements PermissionRepository {

    private final PermissionJpaRepository jpaRepository;

    public SpringPermissionRepository(PermissionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Permission> findById(Long id) {
        return jpaRepository.findById(id).map(PermissionMappers::fromEntity);
    }

    @Override
    public PageResult<Permission> findAll(PermissionFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<PermissionEntity> spec = PermissionSpecifications.from(filter);
        Page<PermissionEntity> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page.map(PermissionMappers::fromEntity));
    }

    @Override
    public Optional<Permission> findByCode(String code) {
        return jpaRepository.findByCode(code).map(PermissionMappers::fromEntity);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpaRepository.existsByCode(code);
    }

    @Override
    public List<Permission> findAll() {
        return jpaRepository.findAll().stream().map(PermissionMappers::fromEntity).toList();
    }
}
