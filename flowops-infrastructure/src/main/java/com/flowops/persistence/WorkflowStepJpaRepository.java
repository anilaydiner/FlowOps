package com.flowops.persistence;

import com.flowops.persistence.entity.WorkflowStepEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowStepJpaRepository extends JpaRepository<WorkflowStepEntity, Long> {

    List<WorkflowStepEntity> findByWorkflowIdOrderById(Long workflowId);

    List<WorkflowStepEntity> deleteByWorkflowId(Long workflowId);

    List<WorkflowStepEntity> findAllByWorkflowIdOrderByPosition(Long workflowId);

}
