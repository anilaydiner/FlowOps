package com.flowops.persistence;

import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.workflow.Workflow;
import com.flowops.domain.workflow.port.WorkflowRepository;
import com.flowops.domain.workflow.query.WorkflowFilter;
import com.flowops.persistence.entity.WorkflowEntity;
import com.flowops.persistence.mapping.WorkflowMappers;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** JPA adapter for the {@link WorkflowRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringWorkflowRepository implements WorkflowRepository {

    private final WorkflowJpaRepository jpaRepository;

    public SpringWorkflowRepository(WorkflowJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Workflow save(Workflow workflow) {
        WorkflowEntity saved = jpaRepository.saveAndFlush(WorkflowMappers.toEntity(workflow));
        backfill(workflow, saved);
        return workflow;
    }

    @Override
    public Optional<Workflow> findById(Long id) {
        return jpaRepository.findById(id).map(WorkflowMappers::fromEntity);
    }

    @Override
    public PageResult<Workflow> findAll(WorkflowFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<WorkflowEntity> spec = WorkflowSpecifications.from(filter);
        Page<WorkflowEntity> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page.map(WorkflowMappers::fromEntity));
    }

    @Override
    public Optional<Workflow> findByCode(String code) {
        return jpaRepository.findByCode(code).map(WorkflowMappers::fromEntity);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpaRepository.existsByCode(code);
    }

    private static void backfill(Workflow d, WorkflowEntity e) {
        d.setId(e.getId());
    }
}
