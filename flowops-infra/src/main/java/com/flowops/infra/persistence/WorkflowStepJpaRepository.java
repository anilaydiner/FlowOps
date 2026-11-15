package com.flowops.infra.persistence;

import com.flowops.infra.persistence.entity.WorkflowStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowStepJpaRepository extends JpaRepository<WorkflowStepEntity, Long> {

    List<WorkflowStepEntity> findByWorkflowIdOrderById(Long workflowId);

    List<WorkflowStepEntity> deleteByWorkflowId(Long workflowId);

    List<WorkflowStepEntity> findAllByWorkflowIdOrderByPosition(Long workflowId);
}
