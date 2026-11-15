package com.flowops.application.usecase;

import com.flowops.domain.policy.AuthorizationDecision;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;
import com.flowops.domain.policy.TargetContext;
import com.flowops.domain.Decision;
import com.flowops.domain.DecisionAction;
import com.flowops.domain.port.DecisionRepository;
import com.flowops.domain.Request;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.port.StepInstanceRepository;
import com.flowops.domain.StepState;
import com.flowops.domain.WorkflowStep;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.application.service.EmailComposer;
import com.flowops.application.service.ExecutionService;
import com.flowops.domain.OutboxEvents;
import com.flowops.application.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final OutboxService outbox;
    private final ExecutionService executionService;
    private final EmailComposer emailComposer;

    public DecideStep(RequestRepository requests,
                      StepInstanceRepository stepInstances,
                      DecisionRepository decisions,
                      StepMaterializer materializer,
                      AuthorizationEvaluator authz,
                      OutboxService outbox,
                      ExecutionService executionService,
                      EmailComposer emailComposer) {
        this.requests = requests;
        this.stepInstances = stepInstances;
        this.decisions = decisions;
        this.materializer = materializer;
        this.authz = authz;
        this.outbox = outbox;
        this.executionService = executionService;
        this.emailComposer = emailComposer;
    }

    @Transactional
    public Request execute(Long requestId, DecisionAction action, String justification, CallerIdentity caller) {
        return execute(requestId, action, justification, caller, null);
    }

    @Transactional
    public Request execute(Long requestId, DecisionAction action, String justification,
                           CallerIdentity caller, Long delegatedTo) {
        if (action == DecisionAction.OVERRIDE) {
            throw new ConflictException("OVERRIDE not available yet");
        }
        if (action == DecisionAction.DELEGATE && delegatedTo == null) {
            throw new ConflictException("DELEGATE requires a delegatedTo user ID");
        }
        Request request = load(requestId);
        if (request.getStatus() != RequestStatus.IN_REVIEW) {
            throw new ConflictException("Request is not in review (current: " + request.getStatus() + ")");
        }
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

        // ── DELEGATE: record only, do NOT advance the workflow ──────────
        if (action == DecisionAction.DELEGATE) {
            Request saved = requests.save(request);
            log.info("DELEGATE recorded: request={} from={} to={} step={}",
                    requestId, caller.username(), delegatedTo, step.getCode());

            // Notify the delegatee
            EmailComposer.ComposedEmail delegateEmail = emailComposer.delegated(
                    request, caller.username(), "the delegatee", step.getCode());
            outbox.notifyFeed(requestId, delegatedTo, OutboxEvents.NOTIFY_DELEGATE,
                    delegateEmail.subject(), delegateEmail.text(), null);
            outbox.notifyEmail(requestId, delegatedTo, OutboxEvents.DELEGATED,
                    delegateEmail.subject(), delegateEmail.text(), delegateEmail.html(), null);

            return saved;
        }

        if (action == DecisionAction.REJECT) {
            request.transitionTo(RequestStatus.REJECTED);
            Request saved = requests.save(request);
            requests.flush(); // surface optimistic-lock conflicts before outbox writes

            // ── Outbox trigger: notify requester of rejection ────────────
            Long requesterId = request.getRequester().getId();
            EmailComposer.ComposedEmail rejectEmail = emailComposer.rejected(
                    request, caller.username(), justification);
            outbox.notifyFeed(requestId, requesterId, OutboxEvents.NOTIFY_REJECT,
                    rejectEmail.subject(), rejectEmail.text(), null);
            outbox.notifyEmail(requestId, requesterId, OutboxEvents.REJECTED,
                    rejectEmail.subject(), rejectEmail.text(), rejectEmail.html(), null);

            return saved;
        }

        // Completion rule: count APPROVE decisions on this step instance
        long approvals = decisions.countByStepInstanceIdAndAction(current.getId(), DecisionAction.APPROVE);
        if (approvals >= current.getRequiredApprovals()) {
            current.satisfy(caller.id());
            stepInstances.save(current);
            materializer.materializeNext(request, step.getWorkflow(), current.getPosition());
        }
        Request saved = requests.save(request);
        requests.flush(); // surface optimistic-lock conflicts before outbox writes

        // ── Outbox trigger: notify requester on approval (if fully approved) ──
        if (request.getStatus() == RequestStatus.APPROVED) {
            Long requesterId = request.getRequester().getId();
            EmailComposer.ComposedEmail approveEmail = emailComposer.approved(request, caller.username());
            outbox.notifyFeed(requestId, requesterId, OutboxEvents.NOTIFY_APPROVE,
                    approveEmail.subject(), approveEmail.text(), null);
            outbox.notifyEmail(requestId, requesterId, OutboxEvents.APPROVED,
                    approveEmail.subject(), approveEmail.text(), approveEmail.html(), null);

            // ── Iteration 7: enqueue execution jobs ─────────────────────
            executionService.enqueueJobs(request);
        }

        return saved;
    }

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
