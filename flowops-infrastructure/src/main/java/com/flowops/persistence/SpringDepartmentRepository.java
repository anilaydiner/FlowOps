package com.flowops.persistence;

import com.flowops.domain.organization.Department;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.organization.query.DepartmentFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.persistence.entity.DepartmentEntity;
import com.flowops.persistence.mapping.DepartmentMappers;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** JPA adapter for the {@link DepartmentRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringDepartmentRepository implements DepartmentRepository {

    private final DepartmentJpaRepository jpaRepository;

    public SpringDepartmentRepository(DepartmentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PageResult<Department> findAll(DepartmentFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<DepartmentEntity> spec = DepartmentSpecifications.from(filter);
        Page<DepartmentEntity> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page.map(DepartmentMappers::fromEntity));
    }

    @Override
    public Optional<Department> findByCode(String code) {
        return jpaRepository.findByCode(code).map(DepartmentMappers::fromEntity);
    }
}
