package com.flowops.infra.persistence;

import com.flowops.domain.Workflow;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.WorkflowRepository;
import com.flowops.domain.query.WorkflowFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SpringWorkflowRepository implements WorkflowRepository {

    private final WorkflowJpaRepository jpaRepository;

    public SpringWorkflowRepository(WorkflowJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Workflow save(Workflow workflow) {
        return jpaRepository.save(workflow);
    }

    @Override
    public Optional<Workflow> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public PageResult<Workflow> findAll(WorkflowFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<Workflow> spec = WorkflowSpecifications.from(filter);
        Page<Workflow> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page);
    }

    @Override
    public Optional<Workflow> findByCode(String code) {
        return jpaRepository.findByCode(code);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpaRepository.existsByCode(code);
    }
}
