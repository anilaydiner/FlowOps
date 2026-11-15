package com.flowops.infra.persistence;

import com.flowops.domain.WorkflowStep;
import com.flowops.domain.port.WorkflowStepRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public class SpringWorkflowStepRepository implements WorkflowStepRepository {

    private final WorkflowStepJpaRepository jpaRepository;

    public SpringWorkflowStepRepository(WorkflowStepJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public WorkflowStep save(WorkflowStep step) {
        return jpaRepository.save(step);
    }

    @Override
    public void deleteAll(Collection<WorkflowStep> steps) {
        jpaRepository.deleteAllInBatch(steps);
    }

    @Override
    public void flush() {
        jpaRepository.flush();
    }

    @Override
    public List<WorkflowStep> findByWorkflowIdOrderById(Long workflowId) {
        return jpaRepository.findByWorkflowIdOrderById(workflowId);
    }
}
