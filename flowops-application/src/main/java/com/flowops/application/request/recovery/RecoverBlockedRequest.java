package com.flowops.application.request.recovery;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.notification.ApproverNotifier;
import com.flowops.application.notification.outbox.OutboxService;
import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.*;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.request.*;
import com.flowops.domain.request.policy.WorkflowDecisionPolicy;
import com.flowops.domain.request.port.*;
import com.flowops.domain.workflow.CompletionRule;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoverBlockedRequest {

    private final java.time.Clock clock;

    private final ReassignRecoveryOwner ownership;

    private final RequestRepository requests;

    private final RequestWriteLock requestWriteLock;

    private final StepInstanceRepository steps;

    private final StepParticipantRepository participants;

    private final ApprovalEligibilityService eligibility;

    private final AuthorizationEvaluator authz;

    private final BlockedStepLifecycle blocking;

    private final LifecycleAuditRecorder audit;

    private final ApproverNotifier notifier;

    private final OutboxService outbox;

    public RecoverBlockedRequest(RequestRepository requests, RequestWriteLock requestWriteLock,
            StepInstanceRepository steps, StepParticipantRepository participants,
            ApprovalEligibilityService eligibility, AuthorizationEvaluator authz, BlockedStepLifecycle blocking,
            LifecycleAuditRecorder audit, ApproverNotifier notifier, OutboxService outbox, java.time.Clock clock,
            ReassignRecoveryOwner ownership) {
        this.ownership = ownership;
        this.clock = clock;
        this.requests = requests;
        this.requestWriteLock = requestWriteLock;
        this.steps = steps;
        this.participants = participants;
        this.eligibility = eligibility;
        this.authz = authz;
        this.blocking = blocking;
        this.audit = audit;
        this.notifier = notifier;
        this.outbox = outbox;
    }

    @Transactional
    public Request recover(Long requestId, Long stepId, Long expectedVersion, String reason, boolean refreshPool,
            List<RecoveryAssignment> assignments, CallerIdentity caller) {
        Request request = load(requestId, stepId, expectedVersion, reason, caller);
        StepInstance step = active(request, stepId);
        if (step.getState() != StepState.BLOCKED)
            throw new ConflictException("Only a blocked step can be recovered");
        restore(request, step, reason.strip(), caller, refreshPool, assignments == null ? List.of() : assignments);
        return request;
    }

    /**
     * Manual entry point for role/account revocation; the future scheduled reconciler
     * will use the same lifecycle.
     */
    @Transactional
    public Request recheck(Long requestId, Long stepId, Long expectedVersion, String reason, CallerIdentity caller) {
        Request request = load(requestId, stepId, expectedVersion, reason, caller);
        StepInstance step = active(request, stepId);
        reconcileLocked(request, step, reason, caller);
        return request;
    }

    /**
     * Parent-locked manual and scheduled checks share one transition path. No roster
     * refresh.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void reconcileLocked(Request request, StepInstance step, String reason, CallerIdentity caller) {
        step.requireUnexpired(clock.instant());
        var reachability = eligibility.reachability(request, step);
        if (step.getState() == StepState.PENDING && reachability.reason() != null) {
            blocking.blockPending(request, step, reachability.reason(), caller);
        }
        else if (step.getState() == StepState.BLOCKED && reachability.reason() == null) {
            restore(request, step, reason.strip(), caller, false, List.of());
        }
        if (step.getState() == StepState.BLOCKED)
            ownership.reconcileLocked(request, step);
    }

    private Request load(Long id, Long stepId, Long version, String reason, CallerIdentity caller) {
        if (version == null || reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new RequestValidationException(
                    Map.of("recovery", "expectedVersion and a reason of 1–1000 characters are required"));
        }
        Request request = requestWriteLock.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Request not found: " + id));
        if (!eligibility.user(caller.id()).isActive())
            throw new ForbiddenException("Inactive recovery operator");
        authz.require(caller, PermissionCodes.REQUEST_RECOVER,
                ApprovalEligibilityService.target(request, active(request, stepId)), true);
        if (!version.equals(request.getVersion()))
            throw new ConflictException("Stale recovery command; reload the request");
        WorkflowDecisionPolicy.requireInReview(request);
        active(request, stepId).requireUnexpired(clock.instant());
        return request;
    }

    private StepInstance active(Request request, Long id) {
        StepInstance step = steps.findActiveForLockedRequest(request.getId())
            .orElseThrow(() -> new ConflictException("Request has no active step"));
        if (id == null || !id.equals(step.getId()))
            throw new ConflictException("Stale recovery step; reload the request");
        return step;
    }

    private void restore(Request request, StepInstance step, String reason, CallerIdentity caller, boolean refreshPool,
            List<RecoveryAssignment> assignments) {
        var roster = eligibility.roster(step);
        var history = eligibility.history(step);
        var replaced = roster.stream()
            .map(StepParticipant::predecessorId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        var voters = history.stream()
            .filter(d -> d.getAction() == DecisionAction.APPROVE)
            .map(Decision::getActorId)
            .collect(Collectors.toSet());
        var priorPeople = roster.stream().map(StepParticipant::userId).collect(Collectors.toSet());
        List<RecoveryAssignment> planned = new ArrayList<>(assignments);
        if (!step.isRosterSnapshotted() && !planned.isEmpty())
            throw new ConflictException("Legacy steps cannot accept fabricated roster assignments");
        var pool = eligibility.candidatesAtActivation(request, step);
        if (refreshPool && step.isRosterSnapshotted() && step.getRule() != CompletionRule.ALL_OF) {
            Set<Long> requestedPeople = planned.stream().map(RecoveryAssignment::userId).collect(Collectors.toSet());
            pool.stream()
                .filter(id -> !priorPeople.contains(id) && !requestedPeople.contains(id))
                .map(id -> new RecoveryAssignment(null, id))
                .forEach(planned::add);
        }
        Set<Long> newPeople = new HashSet<>();
        Set<UUID> changedSlots = new HashSet<>();
        for (RecoveryAssignment assignment : planned) {
            if (assignment == null || assignment.userId() == null || !newPeople.add(assignment.userId())) {
                throw new ConflictException("Recovery assignments must name distinct people");
            }
            if (!eligibility
                .evaluateForRecovery(eligibility.user(assignment.userId()), request, step, history, roster, true)
                .allowed()) {
                throw new ConflictException(
                        "Recovery participant is not eligible or already appears in this step's history");
            }
            if (assignment.predecessorId() == null) {
                if (!pool.contains(assignment.userId()))
                    throw new ConflictException(
                            "New pool participants must hold the required role and current approval authority");
                if (step.getRule() == CompletionRule.ALL_OF && (step.getRosterSize() != 0 || planned.size() != 1)) {
                    throw new ConflictException("ALL_OF recovery must replace an existing unvoted slot");
                }
            }
            else {
                var previous = roster.stream()
                    .filter(p -> p.id().equals(assignment.predecessorId()) && !replaced.contains(p.id()))
                    .findFirst()
                    .orElseThrow(() -> new ConflictException("Replacement must target a current assignment"));
                if (!changedSlots.add(previous.slotId()) || voters.contains(previous.userId())) {
                    throw new ConflictException("An already voted or repeated slot cannot be replaced");
                }
            }
        }
        Instant now = clock.instant();
        UUID recoveryId = UUID.randomUUID();
        participants.recordRecovery(new StepRecovery(recoveryId, step.getId(), step.getBlockEpisode(),
                caller == null ? null : caller.id(), reason, now));
        for (RecoveryAssignment assignment : planned)
            participants.appendRecoveryAssignment(step.getId(), recoveryId, assignment);
        var reachability = eligibility.reachability(request, step);
        if (reachability.reason() != null)
            throw new ConflictException(
                    "Recovery cannot reach the frozen approval threshold: " + reachability.reason());
        step.recover(now);
        request.recordActivity();
        requests.save(request);
        steps.save(step);
        audit.record(request, caller, "STEP", "RECOVERED", BlockedStepLifecycle.key(step) + ":recovered",
                "Step " + step.getStepCode() + " recovered", Map.of("state", "BLOCKED"), Map.of("state", "PENDING"),
                Map.of("stepInstanceId", step.getId(), "blockEpisode", step.getBlockEpisode(), "recoveryId", recoveryId,
                        "votesPreserved", reachability.votes(), "remainingEligible", reachability.remaining().size(),
                        "assignmentCount", planned.size(), "requiredApprovals", step.getRequiredApprovals(),
                        "recoveredAt", now.toString()),
                reason);
        for (StepParticipant p : eligibility.roster(step)) {
            if (recoveryId.equals(p.recoveryId()))
                audit.record(request, caller, "PARTICIPANT", "RECOVERY_ASSIGNED", "assignment:" + p.id(),
                        "Approval slot assigned during recovery", Map.of(), Map.of("userId", p.userId()),
                        Map.of("stepInstanceId", step.getId(), "assignmentId", p.id(), "slotId", p.slotId(),
                                "recoveryId", recoveryId),
                        reason);
        }
        String type = "RECOVERED:" + step.getBlockEpisode();
        outbox.notifyStepFeed(request.getId(), step.getId(), request.getRequester().getId(), type,
                "Request #" + request.getId() + " review resumed", "Step " + step.getStepName() + " recovered",
                request.getCorrelationId());
        notifier.notifyRecoveredApprovers(request, step.getBlockEpisode());
    }

}
