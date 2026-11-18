package com.flowops.persistence;

import com.flowops.domain.workflow.WorkflowStep;
import com.flowops.domain.workflow.port.WorkflowStepRepository;
import com.flowops.persistence.entity.WorkflowStepEntity;
import com.flowops.persistence.mapping.WorkflowStepMappers;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
        step.recordPersistence(saved.getId());
        return step;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceDefinitions(Long workflowId, List<WorkflowStep> definitions) {
        jpaRepository.deleteAllInBatch(jpaRepository.findByWorkflowIdOrderById(workflowId));
        jpaRepository.flush();
        definitions.forEach(this::save);
    }

    @Override
    public List<WorkflowStep> findByWorkflowIdOrderById(Long workflowId) {
        return jpaRepository.findByWorkflowIdOrderById(workflowId).stream().map(WorkflowStepMappers::fromEntity).toList();
    }

    @Override
    public List<WorkflowStep> findInExecutionOrder(Long workflowId) {
        return jpaRepository.findAllByWorkflowIdOrderByPosition(workflowId).stream()
                .map(WorkflowStepMappers::fromEntity).toList();
    }
}
