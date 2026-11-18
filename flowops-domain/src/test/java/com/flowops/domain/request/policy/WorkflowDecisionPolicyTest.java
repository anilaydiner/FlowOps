package com.flowops.domain.request.policy;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.InvalidStateTransitionException;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepInstance;
import com.flowops.domain.request.StepState;
import com.flowops.domain.workflow.CompletionRule;
import com.flowops.domain.workflow.WorkflowStep;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.flowops.domain.request.policy.WorkflowDecisionPolicy.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowDecisionPolicyTest {
    private Request inReview() {
        Request request = Request.newDraft(null, null, Map.of(), "correlation");
        request.markSubmitted(java.time.Instant.now());
        request.beginReview(java.time.Instant.now());
        return request;
    }

    private StepInstance step(Request request, CompletionRule rule, int threshold) {
        WorkflowStep definition = new WorkflowStep(null, 1, "REVIEW", "Review", "APPROVER");
        definition.configure(rule, rule == CompletionRule.ALL_OF ? 1 : threshold, null, null, null, false);
        var instance = new StepInstance(request, definition);
        if (rule == CompletionRule.ALL_OF) instance.snapshotRoster(threshold);
        return instance;
    }

    @ParameterizedTest
    @EnumSource(CompletionRule.class)
    void completionUsesMaterializedThresholdDespiteLaterDefinitionEdits(CompletionRule rule) {
        Request request = inReview();
        int threshold = rule == CompletionRule.ANY_OF ? 1 : 3;
        StepInstance current = step(request, rule, threshold);
        current.getStep().configure(CompletionRule.K_OF_N, 10, null, null, null, false);

        assertEquals(APPROVAL_RECORDED,
                WorkflowDecisionPolicy.apply(request, current, DecisionAction.APPROVE, threshold - 1, 42L));
        assertEquals(StepState.PENDING, current.getState());
        assertNull(current.getSatisfiedBy());
        assertNull(current.getSatisfiedAt());
        assertEquals(STEP_COMPLETED,
                WorkflowDecisionPolicy.apply(request, current, DecisionAction.APPROVE, threshold, 43L));
        assertEquals(StepState.SATISFIED, current.getState());
        assertEquals(43L, current.getSatisfiedBy());
        assertNotNull(current.getSatisfiedAt());
        // The application must materialize the next step before deciding if the request is approved.
        assertEquals(RequestStatus.IN_REVIEW, request.getStatus());
    }

    @Test
    void approvalCountAboveThresholdStillCompletesStep() {
        Request request = inReview();
        StepInstance current = step(request, CompletionRule.K_OF_N, 2);
        assertEquals(STEP_COMPLETED,
                WorkflowDecisionPolicy.apply(request, current, DecisionAction.APPROVE, 3, 42L));
        assertEquals(StepState.SATISFIED, current.getState());
    }

    @Test
    void delegationDoesNotAdvanceOrSatisfyEvenWhenApprovalThresholdIsMet() {
        Request request = inReview();
        StepInstance current = step(request, CompletionRule.ANY_OF, 1);
        assertEquals(DELEGATED,
                WorkflowDecisionPolicy.apply(request, current, DecisionAction.DELEGATE, 1, 42L));
        assertEquals(RequestStatus.IN_REVIEW, request.getStatus());
        assertEquals(StepState.PENDING, current.getState());
        assertNull(current.getSatisfiedAt());
    }

    @Test
    void rejectionEndsRequestAndStopsTheActiveStep() {
        Request request = inReview();
        StepInstance current = step(request, CompletionRule.ANY_OF, 1);
        assertEquals(REJECTED,
                WorkflowDecisionPolicy.apply(request, current, DecisionAction.REJECT, 1, 42L));
        assertEquals(RequestStatus.REJECTED, request.getStatus());
        assertEquals(StepState.REJECTED, current.getState());
        assertNull(current.getSatisfiedBy());
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = "IN_REVIEW", mode = EnumSource.Mode.EXCLUDE)
    void cannotDecideOutsideReview(RequestStatus status) {
        Request request = Request.restore(new Request.Snapshot(1L, null, null, status,
                Map.of(), 0L, null, null, null, null));
        StepInstance current = step(request, CompletionRule.ANY_OF, 1);
        assertThrows(ConflictException.class,
                () -> WorkflowDecisionPolicy.apply(request, current, DecisionAction.APPROVE, 1, 42L));
        assertEquals(status, request.getStatus());
        assertEquals(StepState.PENDING, current.getState());
    }

    @ParameterizedTest
    @EnumSource(value = StepState.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void cannotDecideOnNonPendingStep(StepState state) {
        Request request = inReview();
        StepInstance current = step(request, CompletionRule.ANY_OF, 1);
        current.setState(state);
        assertThrows(InvalidStateTransitionException.class,
                () -> WorkflowDecisionPolicy.apply(request, current, DecisionAction.REJECT, 0, 42L));
        assertEquals(RequestStatus.IN_REVIEW, request.getStatus());
        assertEquals(state, current.getState());
    }

    @Test
    void unsupportedOverrideDoesNotMutateState() {
        Request request = inReview();
        StepInstance current = step(request, CompletionRule.ANY_OF, 1);
        assertThrows(ConflictException.class,
                () -> WorkflowDecisionPolicy.validateAction(DecisionAction.OVERRIDE, null));
        assertThrows(ConflictException.class,
                () -> WorkflowDecisionPolicy.apply(request, current, DecisionAction.OVERRIDE, 1, 42L));
        assertEquals(RequestStatus.IN_REVIEW, request.getStatus());
        assertEquals(StepState.PENDING, current.getState());
    }

    @Test
    void delegationRequiresTargetButOtherSupportedActionsDoNot() {
        assertThrows(ConflictException.class,
                () -> WorkflowDecisionPolicy.validateAction(DecisionAction.DELEGATE, null));
        assertDoesNotThrow(() -> WorkflowDecisionPolicy.validateAction(DecisionAction.DELEGATE, 42L));
        assertDoesNotThrow(() -> WorkflowDecisionPolicy.validateAction(DecisionAction.APPROVE, null));
        assertDoesNotThrow(() -> WorkflowDecisionPolicy.validateAction(DecisionAction.REJECT, null));
    }
}
