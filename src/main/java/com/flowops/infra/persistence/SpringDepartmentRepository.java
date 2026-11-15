package com.flowops.infra.persistence;

import com.flowops.domain.Department;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.DepartmentRepository;
import com.flowops.domain.query.DepartmentFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** JPA adapter for the {@link DepartmentRepository} port. */
@Repository
public class SpringDepartmentRepository implements DepartmentRepository {

    private final DepartmentJpaRepository jpaRepository;

    public SpringDepartmentRepository(DepartmentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PageResult<Department> findAll(DepartmentFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<Department> spec = DepartmentSpecifications.from(filter);
        Page<Department> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page);
    }

    @Override
    public Optional<Department> findByCode(String code) {
        return jpaRepository.findByCode(code);
    }
}
