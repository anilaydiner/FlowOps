package com.flowops.domain.workflow.port;

import com.flowops.domain.workflow.WorkflowStep;
import java.util.List;

/**
 * Persistence port for {@link WorkflowStep} definitions.
 *
 * <p>
 * Implemented by the JPA adapter in {@code persistence}; the domain layer only sees this
 * contract — no Spring Data types.
 */
public interface WorkflowStepRepository {

    WorkflowStep save(WorkflowStep step);

    /** Replace the definitions for this workflow in the current transaction. */
    void replaceDefinitions(Long workflowId, List<WorkflowStep> definitions);

    /** Steps of a workflow in catalog order. */
    List<WorkflowStep> findByWorkflowIdOrderById(Long workflowId);

    /** Steps of a workflow in configured execution order. */
    List<WorkflowStep> findInExecutionOrder(Long workflowId);

}
