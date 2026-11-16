package com.flowops.domain.policy;

import com.flowops.domain.DecisionAction;
import com.flowops.domain.Request;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.StepState;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.InvalidStateTransitionException;

/**
 * Applies the business effect of an authorized, recorded workflow decision.
 * The application owns authorization, decision persistence and the enclosing transaction.
 * This policy performs no I/O; its outcome tells the application whether to advance the workflow.
 */
public final class WorkflowDecisionPolicy {
    private WorkflowDecisionPolicy() {
    }

    public enum Outcome {
        DELEGATED, REJECTED, APPROVAL_RECORDED, STEP_COMPLETED
    }

    public static void validateAction(DecisionAction action, Long delegatedTo) {
        if (action == DecisionAction.OVERRIDE) {
            throw new ConflictException("OVERRIDE not available yet");
        }
        if (action == DecisionAction.DELEGATE && delegatedTo == null) {
            throw new ConflictException("DELEGATE requires a delegatedTo user ID");
        }
    }

    public static void requireInReview(Request request) {
        if (request.getStatus() != RequestStatus.IN_REVIEW) {
            throw new ConflictException("Request is not in review (current: " + request.getStatus() + ")");
        }
    }

    /**
     * approvalsIncludingDecision is the persisted APPROVE count for this step, including
     * the decision just recorded. The materialized step's threshold is authoritative for
     * ANY_OF, ALL_OF and K_OF_N; later definition edits must not change that threshold.
     * Rejection deliberately leaves the step pending, matching the existing audit history.
     */
    public static Outcome apply(Request request, StepInstance current, DecisionAction action,
                                long approvalsIncludingDecision, Long actorId) {
        requireInReview(request);
        if (current.getState() != StepState.PENDING) {
            throw new InvalidStateTransitionException("step instance", current.getState(), "decide");
        }
        return switch (action) {
            case DELEGATE -> Outcome.DELEGATED;
            case REJECT -> {
                request.transitionTo(RequestStatus.REJECTED);
                yield Outcome.REJECTED;
            }
            case APPROVE -> {
                if (approvalsIncludingDecision >= current.getRequiredApprovals()) {
                    current.satisfy(actorId);
                    yield Outcome.STEP_COMPLETED;
                }
                yield Outcome.APPROVAL_RECORDED;
            }
            case OVERRIDE -> throw new ConflictException("OVERRIDE not available yet");
        };
    }
}
