package com.flowops.application.request.workflow;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.StepInstance;
import com.flowops.domain.request.port.StepInstanceRepository;
import com.flowops.domain.request.port.StepParticipantRepository;
import com.flowops.domain.workflow.Workflow;
import com.flowops.domain.workflow.WorkflowStep;
import com.flowops.domain.workflow.policy.ConditionEvaluator;
import com.flowops.domain.workflow.port.WorkflowStepRepository;
import org.springframework.stereotype.Component;

/**
 * Shared workflow-step materialization, used by the {@code SubmitRequest} and
 * {@code DecideStep} use cases.
 *
 * <p>
 * Unmaterialized steps are visited in configured position order. The first step whose
 * condition applies is materialized as a PENDING (or BLOCKED, when no eligible approver
 * exists) {@link StepInstance} and the scan stops. Steps whose condition does not apply
 * are recorded as SKIPPED ("Not Applicable"). When no further step applies, false tells
 * the caller to finalize approval.
 * </p>
 */
@Component
public class StepMaterializer {

    private final java.time.Clock clock;

    private final LifecycleAuditRecorder audit;

    private final StepInstanceRepository stepInstances;

    private final WorkflowStepRepository steps;

    private final ApprovalEligibilityService eligibility;

    private final ConditionEvaluator conditions;

    private final StepParticipantRepository participants;

    private final com.flowops.application.request.recovery.BlockedStepLifecycle blocking;

    public StepMaterializer(StepInstanceRepository stepInstances, WorkflowStepRepository steps,
            ApprovalEligibilityService eligibility, ConditionEvaluator conditions,
            StepParticipantRepository participants, LifecycleAuditRecorder audit,
            com.flowops.application.request.recovery.BlockedStepLifecycle blocking, java.time.Clock clock) {
        this.clock = clock;
        this.blocking = blocking;
        this.audit = audit;
        this.stepInstances = stepInstances;
        this.steps = steps;
        this.eligibility = eligibility;
        this.conditions = conditions;
        this.participants = participants;
    }

    /**
     * Materialize the first applicable unvisited step, including one missed by an older
     * workflow run.
     */
    public boolean materializeNext(Request request, Workflow workflow, CallerIdentity caller) {
        var visited = stepInstances.findMaterializedDefinitionIds(request.getId());
        for (WorkflowStep step : steps.findInExecutionOrder(workflow.getId())) {
            if (visited.contains(step.getId())) {
                continue;
            }
            if (conditions.evaluatesTo(step.getCondition(), request.getPayload())) {
                StepInstance instance = new StepInstance(request, step, clock.instant());
                var roster = eligibility.candidatesAtActivation(request, instance);
                instance.snapshotRoster(roster.size());
                if (roster.size() < instance.getRequiredApprovals()) {
                    blocking.prepareBlock(request, instance, com.flowops.domain.request.policy.ApprovalReachability
                        .blockReason(instance.getRule(), instance.getRequiredApprovals(), 0, roster.size()));
                }
                stepInstances.save(instance);
                participants.createInitialRoster(instance.getId(), roster);
                audit.record(request, caller, "STEP", "ACTIVATED", "step:" + instance.getId() + ":activated",
                        "Step " + instance.getStepCode() + " activated as " + instance.getState(), java.util.Map.of(),
                        java.util.Map.of("state", instance.getState().name(), "rule", instance.getRule().name(),
                                "requiredApprovals", instance.getRequiredApprovals(), "rosterSize", roster.size()),
                        java.util.Map.of("stepInstanceId", instance.getId(), "step", instance.getStepCode()),
                        instance.getBlockedReason());
                audit.record(request, caller, "PARTICIPANT", "ROSTER_CAPTURED", "step:" + instance.getId() + ":roster",
                        "Approval roster captured", java.util.Map.of(), java.util.Map.of("participants", roster),
                        java.util.Map.of("stepInstanceId", instance.getId()), null);
                if (instance.getState() == com.flowops.domain.request.StepState.BLOCKED)
                    blocking.recordEntry(request, instance, caller);
                return true;
            }
            StepInstance skipped = new StepInstance(request, step, clock.instant());
            skipped.snapshotRoster(0);
            skipped.skip("Not Applicable");
            stepInstances.save(skipped);
            audit.record(request, caller, "STEP", "SKIPPED", "step:" + skipped.getId() + ":skipped",
                    "Step " + skipped.getStepCode() + " skipped", java.util.Map.of(),
                    java.util.Map.of("state", "SKIPPED"),
                    java.util.Map.of("stepInstanceId", skipped.getId(), "step", skipped.getStepCode()),
                    "Not Applicable");
        }
        return false;
    }

}
