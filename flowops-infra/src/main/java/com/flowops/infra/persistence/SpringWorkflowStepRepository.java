package com.flowops.infra.persistence;

import com.flowops.domain.WorkflowStep;
import com.flowops.domain.port.WorkflowStepRepository;
import com.flowops.infra.persistence.entity.WorkflowStepEntity;
import com.flowops.infra.persistence.mapping.WorkflowStepMappers;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** JPA adapter for the {@link WorkflowStepRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringWorkflowStepRepository implements WorkflowStepRepository {

    private final WorkflowStepJpaRepository jpaRepository;

    public SpringWorkflowStepRepository(WorkflowStepJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public WorkflowStep save(WorkflowStep step) {
        WorkflowStepEntity saved = jpaRepository.saveAndFlush(WorkflowStepMappers.toEntity(step));
        step.setId(saved.getId());
        return step;
    }

    @Override
    public void deleteAll(Collection<WorkflowStep> steps) {
        jpaRepository.deleteAllInBatch(steps.stream().map(WorkflowStepMappers::toEntity).toList());
    }

    @Override
    public void flush() {
        jpaRepository.flush();
    }

    @Override
    public List<WorkflowStep> findByWorkflowIdOrderById(Long workflowId) {
        return jpaRepository.findByWorkflowIdOrderById(workflowId).stream().map(WorkflowStepMappers::fromEntity).toList();
    }
}
