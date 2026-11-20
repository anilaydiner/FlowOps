package com.flowops.application.request.lifecycle;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.event.DomainEventPublisher;
import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.application.notification.ApproverNotifier;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.application.request.workflow.StepMaterializer;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.policy.AuthorizationDecision;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.Decision;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepInstance;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.event.DecisionDelegated;
import com.flowops.domain.request.event.RequestApproved;
import com.flowops.domain.request.event.RequestRejected;
import com.flowops.domain.request.policy.WorkflowDecisionPolicy.Outcome;
import com.flowops.domain.request.policy.WorkflowDecisionPolicy;
import com.flowops.domain.request.port.DecisionRepository;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.request.port.StepInstanceRepository;
import com.flowops.domain.workflow.WorkflowStep;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: record an APPROVE / REJECT / DELEGATE decision on the current step.
 *
 * <p>Scoped authorization (permission + scope + SoD) is enforced via
 * {@link ApprovalEligibilityService}; the explainable evidence trail is stored in
 * {@code decisions.evidence}. Completion rules advance the workflow — the next
 * applicable step is materialized, or the request is APPROVED when none
 * remain. REJECT ends the workflow; DELEGATE records the transfer without
 * advancing. Commands acquire the parent write lock, reload current state, and
 * recheck the originally targeted version before recording a decision. The
 * {@code @Version} guard also protects stale clients and nonconforming writers.</p>
 */
@Service
public class DecideStep {
    private final java.time.Clock clock;

    private final LifecycleAuditRecorder audit;

    private static final Logger log = LoggerFactory.getLogger(DecideStep.class);

    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final StepInstanceRepository stepInstances;
    private final DecisionRepository decisions;
    private final StepMaterializer materializer;
    private final ApprovalEligibilityService eligibility;
    private final DomainEventPublisher events;
    private final UserRepository users;
    private final ApproverNotifier approverNotifier;
    private final FinalizeApproval finalization;

    public DecideStep(RequestRepository requests,
                      RequestWriteLock requestWriteLock,
                      StepInstanceRepository stepInstances,
                      DecisionRepository decisions,
                      StepMaterializer materializer,
                      ApprovalEligibilityService eligibility,
                      DomainEventPublisher events,
                      UserRepository users,
                      ApproverNotifier approverNotifier, LifecycleAuditRecorder audit, java.time.Clock clock,FinalizeApproval finalization) {
        this.clock = clock;this.finalization=finalization;
        this.audit = audit;
        this.requests = requests;
        this.requestWriteLock = requestWriteLock;
        this.stepInstances = stepInstances;
        this.decisions = decisions;
        this.materializer = materializer;
        this.eligibility = eligibility;
        this.events = events;
        this.users = users;
        this.approverNotifier = approverNotifier;
    }

    @Transactional
    public Request execute(Long requestId, DecisionAction action, String justification, CallerIdentity caller) {
        return execute(requestId, action, justification, caller, null);
    }

    @Transactional
    public Request execute(Long requestId, DecisionAction action, String justification,
                           CallerIdentity caller, Long delegatedTo) {
        return execute(requestId, action, justification, caller, delegatedTo, null, null);
    }

    @Transactional
    public Request execute(Long requestId, DecisionAction action, String justification,
                           CallerIdentity caller, Long delegatedTo, Long stepInstanceId, Long expectedVersion) {
        WorkflowDecisionPolicy.validateAction(action, delegatedTo);
        // Capture the version observed on entry, including for legacy bodies without a version.
        Request observed = load(requestId);
        Long targetVersion = expectedVersion == null ? observed.getVersion() : expectedVersion;
        Request request = requestWriteLock.findByIdForUpdate(requestId)
                .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
        if (!targetVersion.equals(request.getVersion())) {
            throw new ConflictException("Stale workflow decision; reload the request");
        }
        WorkflowDecisionPolicy.requireInReview(request);
        StepInstance current = stepInstances.findPendingForLockedRequest(requestId)
                .orElseThrow(() -> new ConflictException("No active step to decide on request " + requestId));
        current.requireUnexpired(clock.instant());
        WorkflowStep step = current.getStep();

        if ((stepInstanceId != null && !stepInstanceId.equals(current.getId()))
                || (expectedVersion != null && !expectedVersion.equals(request.getVersion()))) {
            throw new ConflictException("Stale workflow decision; reload the request");
        }
        var requestBefore = LifecycleAuditRecorder.state(request);
        var history = eligibility.history(current);
        if (history.stream().anyMatch(d -> d.getAction() == DecisionAction.APPROVE && caller.id().equals(d.getActorId()))) {
            throw new ConflictException("An approval from this person is already recorded for this step");
        }
        AuthorizationDecision decision = eligibility.require(caller.id(), request, current, action, history);

        // ── DELEGATE: the delegatee must be able to approve this request too ──
        if (action == DecisionAction.DELEGATE && delegatedTo != null) {
            if (delegatedTo.equals(caller.id())) {
                throw new ConflictException("Cannot delegate a decision to yourself");
            }
            User delegatee = users.findById(delegatedTo)
                    .orElseThrow(() -> new NotFoundException("Delegatee not found: " + delegatedTo));
            if (!delegatee.isActive()) {
                throw new ConflictException("Delegatee is not active: " + delegatedTo);
            }
            AuthorizationDecision delegateeDecision = eligibility.evaluate(delegatee, request, current,
                    DecisionAction.APPROVE, history, true);
            if (!delegateeDecision.allowed()) {
                log.info("DELEGATE DENY: delegatee {} not eligible on request {} — {}",
                        delegatedTo, requestId, delegateeDecision.evidence());
                throw new ForbiddenException(
                        "Delegatee is not eligible to approve this request: " + delegateeDecision.evidence());
            }
        }

        // Build evidence map for the decision record
        Map<String, Object> evidenceMap = new LinkedHashMap<>();
        evidenceMap.put("step", current.getStepCode());
        evidenceMap.put("requiredRole", current.getRequiredRole());
        evidenceMap.put("authorization", decision.toEvidenceMap());
        if (action == DecisionAction.DELEGATE && delegatedTo != null) {
            // Stored as a String on purpose: Hibernate's JSON dirty-check round-trips the
            // in-memory evidence Map, and a Long survives that round-trip as an Integer
            // (Long(4) != Integer(4)), which falsely flags the immutable Decision as dirty
            // and triggers an UPDATE that the V9 append-only trigger rejects. A String is
            // JSON-stable, so no spurious UPDATE. (delegatedTo is still a real Long column.)
            evidenceMap.put("delegatedTo", String.valueOf(delegatedTo));
        }

        // Force the request version guard before any decision/vote/outbox INSERT.
        // A concurrent command retains its original step and version and cannot act on the next step.
        request.recordDecision();
        requests.save(request);

        Decision decisionRecord = new Decision(request, current, caller.id(), caller.username(),
                action, justification, evidenceMap, delegatedTo);
        decisions.save(decisionRecord);
        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("decisionId", decisionRecord.getId());
        auditDetails.put("stepInstanceId", current.getId());
        auditDetails.put("step", current.getStepCode());
        auditDetails.put("action", action.name());
        auditDetails.put("authorization", decision.toEvidenceMap());
        if (delegatedTo != null) auditDetails.put("delegatedTo", delegatedTo);
        audit.record(request, caller, "DECISION", action.name(), "decision:" + decisionRecord.getId(),
                action + " on step " + current.getStepCode(), requestBefore, LifecycleAuditRecorder.state(request), auditDetails, justification);
        if (action == DecisionAction.DELEGATE && current.isRosterSnapshotted()) {
            var successor = eligibility.roster(current).stream().filter(p -> decisionRecord.getId().equals(p.decisionId())).findFirst().orElseThrow();
            audit.record(request, caller, "PARTICIPANT", "SLOT_DELEGATED", "assignment:" + successor.id(),
                    "Approval slot delegated", Map.of("userId", caller.id()), Map.of("userId", delegatedTo),
                    Map.of("stepInstanceId", current.getId(), "slotId", successor.slotId(), "assignmentId", successor.id(),
                            "predecessorId", successor.predecessorId(), "decisionId", decisionRecord.getId()), justification);
        }

        // Count after saving so the policy sees the newly recorded approval.
        long approvals = action == DecisionAction.APPROVE
                ? decisions.countDistinctApprovers(current.getId())
                : 0;
        Outcome outcome = WorkflowDecisionPolicy.apply(request, current, action, approvals, caller.id(),clock.instant());

        if (outcome == Outcome.DELEGATED) {
            Request saved = requests.save(request);
            log.info("DELEGATE recorded: request={} from={} to={} step={}",
                    requestId, caller.username(), delegatedTo, current.getStepCode());

            events.publish(new DecisionDelegated(saved.getId(), caller.username(), delegatedTo, current.getStepCode()));

            return saved;
        }

        if (outcome == Outcome.REJECTED) {
            stepInstances.save(current);
            audit.record(request, caller, "STEP", "REJECTED", "step:" + current.getId() + ":rejected",
                    "Step rejected", Map.of("state", "PENDING"), Map.of("state", "REJECTED"),
                    Map.of("stepInstanceId", current.getId()), justification);
            Request saved = requests.save(request);

            audit.transition(saved, caller, requestBefore, justification);
            events.publish(new RequestRejected(saved.getId(), caller.username(), justification));

            return saved;
        }

        if (outcome == Outcome.STEP_COMPLETED) {
            stepInstances.save(current);
            audit.record(request, caller, "STEP", "SATISFIED", "step:" + current.getId() + ":satisfied",
                    "Step " + current.getStepCode() + " satisfied", Map.of("state", "PENDING"), Map.of("state", "SATISFIED"),
                    Map.of("stepInstanceId", current.getId(), "step", current.getStepCode()), justification);
            if (!materializer.materializeNext(request, step.getWorkflow(), caller)) {
                requests.save(request);
                return finalization.execute(requestId,com.flowops.domain.request.ApprovalEvidence.human(caller.id(),caller.username(),decisionRecord.getId(),current.getId()));
            }

            // ── Phase 8: a new step is awaiting approval → notify its eligible approvers ──
            // (exclude the actor — they just acted on this request)
            if (request.getStatus() == RequestStatus.IN_REVIEW) {
                approverNotifier.notifyEligibleApprovers(request, caller.id());
            }
        }
        Request saved = requests.save(request);

        audit.transition(saved, caller, requestBefore, justification);

        return saved;
    }

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
