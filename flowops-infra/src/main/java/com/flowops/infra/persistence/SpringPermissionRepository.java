package com.flowops.infra.persistence;

import com.flowops.domain.Permission;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.PermissionRepository;
import com.flowops.domain.query.PermissionFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** JPA adapter for the {@link PermissionRepository} port. */
@Repository
public class SpringPermissionRepository implements PermissionRepository {

    private final PermissionJpaRepository jpaRepository;

    public SpringPermissionRepository(PermissionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Permission> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public PageResult<Permission> findAll(PermissionFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<Permission> spec = PermissionSpecifications.from(filter);
        Page<Permission> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page);
    }

    @Override
    public Optional<Permission> findByCode(String code) {
        return jpaRepository.findByCode(code);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpaRepository.existsByCode(code);
    }
}
