package com.flowops.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, Long> {

    /** Steps of a workflow in execution order. */
    List<WorkflowStep> findByWorkflowIdOrderById(Long workflowId);

    List<WorkflowStep> deleteByWorkflowId(Long workflowId);

    List<WorkflowStep> findAllByWorkflowIdOrderByPosition(Long workflowId);
}
