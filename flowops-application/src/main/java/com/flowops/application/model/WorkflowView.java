package com.flowops.application.model;

import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowStep;

import java.util.List;

/**
 * Read model over the workflow aggregate: the workflow plus its ordered steps.
 *
 * <p>Returned by the application service and mapped to the API's
 * {@code WorkflowResponse} DTO by the controller. Keeps the service free of
 * any dependency on the API layer.</p>
 */
public record WorkflowView(Workflow workflow, List<WorkflowStep> steps) {}
