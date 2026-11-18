package com.flowops.application.request;

import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.policy.AuthorizationDecision;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.Decision;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepInstance;
import com.flowops.domain.request.StepParticipant;
import com.flowops.domain.request.port.StepParticipantRepository;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Authority;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.StepFacts;
import com.flowops.domain.request.port.DecisionRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Loads eligibility facts; the domain policy owns the decision and the evidence. */
@Service
public class ApprovalEligibilityService {
    private final java.time.Clock clock;
    private final UserRepository users;
    private final DecisionRepository decisions;
    private final StepParticipantRepository participants;

    public ApprovalEligibilityService(UserRepository users, DecisionRepository decisions, StepParticipantRepository participants, java.time.Clock clock) {
        this.clock = clock;
        this.users = users;
        this.decisions = decisions;
        this.participants = participants;
    }

    public User user(Long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    public Authority authority(Long id, DecisionAction action) {
        return ApprovalEligibilityPolicy.authority(user(id), action);
    }

    public List<Decision> history(StepInstance step) {
        return step.getId() == null ? List.of() : decisions.findByStepInstanceIdOrderById(step.getId());
    }

    public AuthorizationDecision evaluate(User user, Request request, StepInstance step,
                                          DecisionAction action, List<Decision> history,
                                          boolean proposedDelegation) {
        return evaluate(user, request, step, action, history, proposedDelegation, roster(step));
    }

    public List<StepParticipant> roster(StepInstance step) {
        return step.isRosterSnapshotted() && step.getId() != null
                ? participants.findByStepInstanceId(step.getId()) : List.of();
    }

    public AuthorizationDecision evaluate(User user, Request request, StepInstance step,
                                          DecisionAction action, List<Decision> history,
                                          boolean proposedDelegation, List<StepParticipant> roster) {
        return evaluate(user, request, step, action, history, proposedDelegation, roster, false, false);
    }

    public boolean canRead(User user, Request request, StepInstance step) {
        var history = history(step);
        var roster = roster(step);
        return evaluate(user, request, step, DecisionAction.APPROVE, history, false, roster, true, false).allowed()
                || evaluate(user, request, step, DecisionAction.REJECT, history, false, roster, true, false).allowed();
    }

    private AuthorizationDecision evaluate(User user, Request request, StepInstance step,
                                          DecisionAction action, List<Decision> history, boolean proposedDelegation,
                                          List<StepParticipant> roster, boolean reading, boolean assumePending) {
        if (step.isExpired(clock.instant())) return AuthorizationDecision.deny("business-deadline=elapsed");
        Long id = user.getId();
        if (proposedDelegation && step.isRosterSnapshotted() && roster.stream().anyMatch(p -> id.equals(p.userId()))) {
            return AuthorizationDecision.deny("participation=already-in-roster");
        }
        boolean member = roster.stream().anyMatch(p -> id.equals(p.userId())
                && roster.stream().noneMatch(next -> p.id().equals(next.predecessorId())));
        boolean incoming = proposedDelegation || (!step.isRosterSnapshotted() && history.stream().anyMatch(d ->
                d.getAction() == DecisionAction.DELEGATE && id.equals(d.getDelegatedTo())));
        boolean outgoing = history.stream().anyMatch(d -> d.getAction() == DecisionAction.DELEGATE && id.equals(d.getActorId()));
        boolean voted = history.stream().anyMatch(d -> d.getAction() == DecisionAction.APPROVE && id.equals(d.getActorId()));
        return ApprovalEligibilityPolicy.evaluate(ApprovalEligibilityPolicy.authority(user, action),
                new StepFacts(request.getStatus(), assumePending ? com.flowops.domain.request.StepState.PENDING : step.getState(), step.getRequiredRole(),
                        target(request, step), incoming, outgoing, !reading && voted, step.isRosterSnapshotted(), member));
    }

    public AuthorizationDecision require(Long actorId, Request request, StepInstance step, DecisionAction action) {
        return require(actorId, request, step, action, history(step));
    }

    public AuthorizationDecision require(Long actorId, Request request, StepInstance step,
                                          DecisionAction action, List<Decision> history) {
        AuthorizationDecision result = evaluate(user(actorId), request, step, action, history, false);
        if (!result.allowed()) throw new ForbiddenException("Not eligible for this workflow step: " + String.join("; ", result.evidence()));
        return result;
    }

    public static TargetContext target(Request request, StepInstance step) {
        return RequestTargets.from(request, step);
    }

    /** Resolve eligible role members exactly once at activation, before the roster is frozen. */
    public List<Long> candidatesAtActivation(Request request, StepInstance step) {
        TargetContext target = target(request, step);
        return users.findAllWithDepartment().stream().filter(user -> ApprovalEligibilityPolicy.evaluate(
                ApprovalEligibilityPolicy.authority(user, DecisionAction.APPROVE), new StepFacts(
                        RequestStatus.IN_REVIEW, com.flowops.domain.request.StepState.PENDING, step.getRequiredRole(), target,
                        false, false, false)).allowed()).map(User::getId).distinct().sorted().toList();
    }

    /** Hypothetical PENDING evaluation for reachability, never an authorization to vote on BLOCKED. */
    public AuthorizationDecision evaluateForRecovery(User user, Request request, StepInstance step,
                                                      List<Decision> history, List<StepParticipant> roster,
                                                      boolean proposedAssignment) {
        return evaluate(user, request, step, DecisionAction.APPROVE, history, proposedAssignment, roster, false, true);
    }

    public record Reachability(long votes, List<Long> remaining, com.flowops.domain.request.BlockReason reason) {}

    public Reachability reachability(Request request, StepInstance step) {
        var history = history(step);
        var roster = roster(step);
        var remaining = users.findAllWithDepartment().stream().filter(u ->
                evaluateForRecovery(u, request, step, history, roster, false).allowed())
                .map(User::getId).distinct().sorted().toList();
        long votes = decisions.countDistinctApprovers(step.getId());
        var reason = com.flowops.domain.request.policy.ApprovalReachability.blockReason(
                step.getRule(), step.getRequiredApprovals(), votes, remaining.size());
        if (reason != null && step.getRule() == com.flowops.domain.workflow.CompletionRule.ALL_OF
                && step.getRosterSize() != null && step.getRosterSize() > 0) {
            reason = com.flowops.domain.request.BlockReason.PARTICIPANT_UNAVAILABLE;
        }
        return new Reachability(votes, remaining, reason);
    }

    public long eligibleAtActivation(Request request, StepInstance step) {
        return candidatesAtActivation(request, step).size();
    }
}
