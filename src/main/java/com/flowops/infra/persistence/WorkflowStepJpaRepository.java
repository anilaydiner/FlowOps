package com.flowops.infra.persistence;

import com.flowops.domain.WorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowStepJpaRepository extends JpaRepository<WorkflowStep, Long> {

    List<WorkflowStep> findByWorkflowIdOrderById(Long workflowId);

    List<WorkflowStep> deleteByWorkflowId(Long workflowId);

    List<WorkflowStep> findAllByWorkflowIdOrderByPosition(Long workflowId);
}
