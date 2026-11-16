package com.flowops.application.request.lifecycle;

import com.flowops.application.event.DomainEventPublisher;
import com.flowops.application.notification.ApproverNotifier;
import com.flowops.application.request.workflow.StepMaterializer;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.policy.AuthorizationDecision;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
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
 * {@link AuthorizationEvaluator}; the explainable evidence trail is stored in
 * {@code decisions.evidence}. Completion rules advance the workflow — the next
 * applicable step is materialized, or the request is APPROVED when none
 * remain. REJECT ends the workflow; DELEGATE records the transfer without
 * advancing. Every mutation commits through the request's {@code @Version}
 * guard, so concurrent decisions serialize: the loser gets 409.</p>
 */
@Service
public class DecideStep {

    private static final Logger log = LoggerFactory.getLogger(DecideStep.class);

    private final RequestRepository requests;
    private final StepInstanceRepository stepInstances;
    private final DecisionRepository decisions;
    private final StepMaterializer materializer;
    private final AuthorizationEvaluator authz;
    private final DomainEventPublisher events;
    private final UserRepository users;
    private final ApproverNotifier approverNotifier;

    public DecideStep(RequestRepository requests,
                      StepInstanceRepository stepInstances,
                      DecisionRepository decisions,
                      StepMaterializer materializer,
                      AuthorizationEvaluator authz,
                      DomainEventPublisher events,
                      UserRepository users,
                      ApproverNotifier approverNotifier) {
        this.requests = requests;
        this.stepInstances = stepInstances;
        this.decisions = decisions;
        this.materializer = materializer;
        this.authz = authz;
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
        WorkflowDecisionPolicy.validateAction(action, delegatedTo);
        Request request = load(requestId);
        WorkflowDecisionPolicy.requireInReview(request);
        StepInstance current = stepInstances.findTopByRequestIdAndStateOrderById(requestId, StepState.PENDING)
                .orElseThrow(() -> new ConflictException("No active step to decide on request " + requestId));
        WorkflowStep step = current.getStep();

        // ── Scoped authorization (iteration 5) ──────────────────────────
        String permCode = switch (action) {
            case APPROVE, DELEGATE -> PermissionCodes.REQUEST_APPROVE;
            case REJECT -> PermissionCodes.REQUEST_REJECT;
            case OVERRIDE -> PermissionCodes.REQUEST_APPROVE;
        };

        TargetContext target = TargetContext.from(request, step);
        AuthorizationDecision decision = authz.authorize(caller, permCode, target);

        if (!decision.allowed()) {
            log.info("AUTHZ DENY: {} on request {} — {}", caller.username(), requestId, decision.evidence());
            throw new ForbiddenException("Not authorized: " + decision.evidence());
        }

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
            AuthorizationDecision delegateeDecision = authz.evaluate(delegatee, permCode, target);
            if (!delegateeDecision.allowed()) {
                log.info("DELEGATE DENY: delegatee {} not eligible on request {} — {}",
                        delegatedTo, requestId, delegateeDecision.evidence());
                throw new ForbiddenException(
                        "Delegatee is not eligible to approve this request: " + delegateeDecision.evidence());
            }
        }

        // Build evidence map for the decision record
        Map<String, Object> evidenceMap = new LinkedHashMap<>();
        evidenceMap.put("step", step.getCode());
        evidenceMap.put("requiredRole", step.getRequiredRole());
        evidenceMap.put("authorization", decision.toEvidenceMap());
        if (action == DecisionAction.DELEGATE && delegatedTo != null) {
            // Stored as a String on purpose: Hibernate's JSON dirty-check round-trips the
            // in-memory evidence Map, and a Long survives that round-trip as an Integer
            // (Long(4) != Integer(4)), which falsely flags the immutable Decision as dirty
            // and triggers an UPDATE that the V9 append-only trigger rejects. A String is
            // JSON-stable, so no spurious UPDATE. (delegatedTo is still a real Long column.)
            evidenceMap.put("delegatedTo", String.valueOf(delegatedTo));
        }

        Decision decisionRecord = new Decision(request, current, caller.id(), caller.username(),
                action, justification, evidenceMap, delegatedTo);
        decisions.save(decisionRecord);

        // Count after saving so the policy sees the newly recorded approval.
        long approvals = action == DecisionAction.APPROVE
                ? decisions.countByStepInstanceIdAndAction(current.getId(), DecisionAction.APPROVE)
                : 0;
        Outcome outcome = WorkflowDecisionPolicy.apply(request, current, action, approvals, caller.id());

        if (outcome == Outcome.DELEGATED) {
            Request saved = requests.save(request);
            log.info("DELEGATE recorded: request={} from={} to={} step={}",
                    requestId, caller.username(), delegatedTo, step.getCode());

            events.publish(new DecisionDelegated(saved.getId(), caller.username(), delegatedTo, step.getCode()));

            return saved;
        }

        if (outcome == Outcome.REJECTED) {
            Request saved = requests.save(request);
            requests.flush(); // surface optimistic-lock conflicts before outbox writes

            events.publish(new RequestRejected(saved.getId(), caller.username(), justification));

            return saved;
        }

        if (outcome == Outcome.STEP_COMPLETED) {
            stepInstances.save(current);
            materializer.materializeNext(request, step.getWorkflow(), current.getPosition());

            // ── Phase 8: a new step is awaiting approval → notify its eligible approvers ──
            // (exclude the actor — they just acted on this request)
            if (request.getStatus() == RequestStatus.IN_REVIEW) {
                approverNotifier.notifyEligibleApprovers(request, caller.id());
            }
        }
        Request saved = requests.save(request);
        requests.flush(); // surface optimistic-lock conflicts before outbox writes

        if (saved.getStatus() == RequestStatus.APPROVED) {
            events.publish(new RequestApproved(saved.getId(), caller.username()));
        }

        return saved;
    }

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
