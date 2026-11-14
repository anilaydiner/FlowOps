package com.flowops.service.workflow;

import com.flowops.domain.Request;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.StepInstanceRepository;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowStep;
import com.flowops.domain.WorkflowStepRepository;
import com.flowops.domain.UserRepository;
import com.flowops.service.ConditionEvaluator;
import org.springframework.stereotype.Component;

/**
 * Shared workflow-step materialization, used by the {@code SubmitRequest} and
 * {@code DecideStep} use cases.
 *
 * <p>From a given position in a workflow, steps are visited in order: the first
 * step whose condition applies is materialized as a PENDING (or BLOCKED, when
 * no eligible approver exists) {@link StepInstance} and the scan stops. Steps
 * whose condition does not apply are recorded as SKIPPED ("Not Applicable").
 * When no further step applies, the request is transitioned to APPROVED.</p>
 */
@Component
public class StepMaterializer {

    private final StepInstanceRepository stepInstances;
    private final WorkflowStepRepository steps;
    private final UserRepository users;
    private final ConditionEvaluator conditions;

    public StepMaterializer(StepInstanceRepository stepInstances,
                            WorkflowStepRepository steps,
                            UserRepository users,
                            ConditionEvaluator conditions) {
        this.stepInstances = stepInstances;
        this.steps = steps;
        this.users = users;
        this.conditions = conditions;
    }

    /** Materialize the first applicable step after {@code fromPosition}; approve the request when none remain. */
    public void materializeNext(Request request, Workflow workflow, int fromPosition) {
        for (WorkflowStep step : steps.findByWorkflowIdOrderById(workflow.getId())) {
            if (step.getPosition() <= fromPosition) {
                continue;
            }
            if (conditions.evaluatesTo(step.getCondition(), request.getPayload())) {
                StepInstance instance = new StepInstance(request, step);
                if (!eligible(step, request)) {
                    instance.block("No eligible approver for role " + step.getRequiredRole());
                }
                stepInstances.save(instance);
                return;
            }
            StepInstance skipped = new StepInstance(request, step);
            skipped.skip("Not Applicable");
            stepInstances.save(skipped);
        }
        request.transitionTo(RequestStatus.APPROVED);
    }

    /**
     * Eligibility probe: at least one active user with the step's required role
     * other than the requester (SoD-aware).
     */
    private boolean eligible(WorkflowStep step, Request request) {
        return users.countActiveByRoleCode(step.getRequiredRole(), request.getRequester().getId()) > 0;
    }
}
