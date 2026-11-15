package com.flowops.domain.port;

import com.flowops.domain.WorkflowStep;

import java.util.Collection;
import java.util.List;

/**
 * Persistence port for {@link WorkflowStep} definitions.
 *
 * <p>Implemented by the JPA adapter in {@code infra.persistence}; the domain
 * layer only sees this contract — no Spring Data types.
 */
public interface WorkflowStepRepository {

    WorkflowStep save(WorkflowStep step);

    /** Delete all given step definitions. */
    void deleteAll(Collection<WorkflowStep> steps);

    /** Flush pending writes of the persistence context. */
    void flush();

    /** Steps of a workflow in execution order. */
    List<WorkflowStep> findByWorkflowIdOrderById(Long workflowId);
}
