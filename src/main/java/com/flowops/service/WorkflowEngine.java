package com.flowops.service;

import com.flowops.authz.AuthorizationDecision;
import com.flowops.authz.AuthorizationEvaluator;
import com.flowops.authz.TargetContext;
import com.flowops.domain.Decision;
import com.flowops.domain.DecisionAction;
import com.flowops.domain.Request;
import com.flowops.domain.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.StepInstanceRepository;
import com.flowops.domain.StepState;
import com.flowops.domain.UserRepository;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowStep;
import com.flowops.domain.WorkflowStepRepository;
import com.flowops.domain.DecisionRepository;
import com.flowops.observability.FlowOpsSpans;
import com.flowops.security.UserPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow engine v2 (PLAN §6-7, iterations 4-5):
 *
 * <ul>
 *   <li>{@code submit} — DRAFT → (SUBMITTED) → IN_REVIEW; materializes the first
 *       applicable step; conditional steps that don't apply are recorded as
 *       SKIPPED (Not Applicable).</li>
 *   <li>{@code decide} — APPROVE/REJECT on the current step with scoped
 *       authorization (permission + scope + SoD); completion rules
 *       (ANY_OF/ALL_OF/K_OF_N) advance the workflow; REJECT ends it.</li>
 *   <li>{@code close} — EXECUTED|REJECTED → CLOSED (terminal).</li>
 * </ul>
 *
 * <p>Every mutation commits through the request's {@code @Version} guard, so
 * concurrent decisions on the same request serialize: the loser gets 409.
 *
 * <p><b>Iteration 5</b>: authorization is delegated to
 * {@link AuthorizationEvaluator} which checks permission, scope, and SoD,
 * returning an explainable evidence trail stored in {@code decisions.evidence}.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final RequestRepository requests;
    private final StepInstanceRepository stepInstances;
    private final DecisionRepository decisions;
    private final WorkflowStepRepository steps;
    private final UserRepository users;
    private final ConditionEvaluator conditions;
    private final AuthorizationEvaluator authz;
    private final OutboxService outbox;
    private final ExecutionService executionService;
    private final EmailComposer emailComposer;
    private final MeterRegistry metrics;
    private final FlowOpsSpans spans;

    public WorkflowEngine(RequestRepository requests,
                          StepInstanceRepository stepInstances,
                          DecisionRepository decisions,
                          WorkflowStepRepository steps,
                          UserRepository users,
                          ConditionEvaluator conditions,
                          AuthorizationEvaluator authz,
                          OutboxService outbox,
                          ExecutionService executionService,
                          EmailComposer emailComposer,
                          MeterRegistry metrics,
                          FlowOpsSpans spans) {
        this.requests = requests;
        this.stepInstances = stepInstances;
        this.decisions = decisions;
        this.steps = steps;
        this.users = users;
        this.conditions = conditions;
        this.authz = authz;
        this.outbox = outbox;
        this.executionService = executionService;
        this.emailComposer = emailComposer;
        this.metrics = metrics;
        this.spans = spans;
    }

    // ── submit ──────────────────────────────────────────────────────────

    @Transactional
    public Request submit(Long requestId, UserPrincipal caller) {
        // One span per lifecycle event (iteration 8 tracing)
        return spans.run("request.submit", Map.of(
                        "request.id", String.valueOf(requestId),
                        "user", caller.username()),
                () -> doSubmit(requestId, caller));
    }

    private Request doSubmit(Long requestId, UserPrincipal caller) {
        Request request = load(requestId);
        if (!request.isDraft()) {
            throw new ConflictException("Only DRAFT requests can be submitted (current: " + request.getStatus() + ")");
        }
        Workflow workflow = request.getType().getDefaultWorkflow();
        if (workflow == null) {
            throw new ConflictException("Request type has no default workflow: " + request.getType().getCode());
        }
        if (!workflow.isActive()) {
            throw new ConflictException("Workflow is inactive: " + workflow.getCode());
        }

        request.markSubmitted(Instant.now());
        materializeFrom(request, steps.findByWorkflowIdOrderById(workflow.getId()), 0);
        if (request.getStatus() != RequestStatus.APPROVED) {
            request.transitionTo(RequestStatus.IN_REVIEW);
        }
        Request saved = requests.save(request);
        requests.flush(); // commit the state transition before touching the outbox

        // ── Outbox trigger: notify on submit (PLAN §8.1) ──────────────────
        String typeCode = request.getType().getCode();
        Long requesterId = request.getRequester().getId();
        EmailComposer.ComposedEmail submitEmail = emailComposer.submitted(request);
        outbox.notifyFeed(requestId, requesterId, "NOTIFY_SUBMIT",
                submitEmail.subject(), submitEmail.text(), null);
        outbox.notifyEmail(requestId, requesterId, "SUBMITTED",
                submitEmail.subject(), submitEmail.text(), submitEmail.html(), null);

        metrics.counter("flowops.request.submitted").increment();
        return saved;
    }

    // ── decide ──────────────────────────────────────────────────────────

    @Transactional
    public Request decide(Long requestId, DecisionAction action, String justification, UserPrincipal caller) {
        return decide(requestId, action, justification, caller, null);
    }

    @Transactional
    public Request decide(Long requestId, DecisionAction action, String justification,
                          UserPrincipal caller, Long delegatedTo) {
        return spans.run("request.decide", Map.of(
                        "request.id", String.valueOf(requestId),
                        "user", caller.username(),
                        "action", String.valueOf(action)),
                () -> doDecide(requestId, action, justification, caller, delegatedTo));
    }

    private Request doDecide(Long requestId, DecisionAction action, String justification,
                             UserPrincipal caller, Long delegatedTo) {
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
            case APPROVE, DELEGATE -> AuthorizationEvaluator.PERM_APPROVE;
            case REJECT -> AuthorizationEvaluator.PERM_REJECT;
            case OVERRIDE -> AuthorizationEvaluator.PERM_APPROVE;
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
            metrics.counter("flowops.request.decided", "action", "DELEGATE").increment();
            log.info("DELEGATE recorded: request={} from={} to={} step={}",
                    requestId, caller.username(), delegatedTo, step.getCode());

            // Notify the delegatee
            EmailComposer.ComposedEmail delegateEmail = emailComposer.delegated(
                    request, caller.username(), "the delegatee", step.getCode());
            outbox.notifyFeed(requestId, delegatedTo, "NOTIFY_DELEGATE",
                    delegateEmail.subject(), delegateEmail.text(), null);
            outbox.notifyEmail(requestId, delegatedTo, "DELEGATED",
                    delegateEmail.subject(), delegateEmail.text(), delegateEmail.html(), null);

            return saved;
        }

        if (action == DecisionAction.REJECT) {
            request.transitionTo(RequestStatus.REJECTED);
            Request saved = requests.save(request);
            metrics.counter("flowops.request.decided", "action", "REJECT").increment();
            requests.flush(); // surface optimistic-lock conflicts before outbox writes

            // ── Outbox trigger: notify requester of rejection ────────────
            Long requesterId = request.getRequester().getId();
            EmailComposer.ComposedEmail rejectEmail = emailComposer.rejected(
                    request, caller.username(), justification);
            outbox.notifyFeed(requestId, requesterId, "NOTIFY_REJECT",
                    rejectEmail.subject(), rejectEmail.text(), null);
            outbox.notifyEmail(requestId, requesterId, "REJECTED",
                    rejectEmail.subject(), rejectEmail.text(), rejectEmail.html(), null);

            return saved;
        }

        // Completion rule: count APPROVE decisions on this step instance
        long approvals = decisions.countByStepInstanceIdAndAction(current.getId(), DecisionAction.APPROVE);
        if (approvals >= current.getRequiredApprovals()) {
            current.satisfy(caller.id());
            stepInstances.save(current);
            List<WorkflowStep> all = steps.findByWorkflowIdOrderById(step.getWorkflow().getId());
            materializeFrom(request, all, current.getPosition());
        }
        Request saved = requests.save(request);
        metrics.counter("flowops.request.decided", "action", "APPROVE").increment();
        requests.flush(); // surface optimistic-lock conflicts before outbox writes

        // ── Outbox trigger: notify requester on approval (if fully approved) ──
        if (request.getStatus() == RequestStatus.APPROVED) {
            metrics.counter("flowops.request.approved").increment();
            Long requesterId = request.getRequester().getId();
            EmailComposer.ComposedEmail approveEmail = emailComposer.approved(request, caller.username());
            outbox.notifyFeed(requestId, requesterId, "NOTIFY_APPROVE",
                    approveEmail.subject(), approveEmail.text(), null);
            outbox.notifyEmail(requestId, requesterId, "APPROVED",
                    approveEmail.subject(), approveEmail.text(), approveEmail.html(), null);

            // ── Iteration 7: enqueue execution jobs ─────────────────────
            executionService.enqueueJobs(request);
        }

        return saved;
    }

    // ── close ───────────────────────────────────────────────────────────

    @Transactional
    public Request close(Long requestId, UserPrincipal caller) {
        return spans.run("request.close", Map.of(
                        "request.id", String.valueOf(requestId),
                        "user", caller.username()),
                () -> doClose(requestId, caller));
    }

    private Request doClose(Long requestId, UserPrincipal caller) {
        Request request = load(requestId);
        if (request.getStatus() != RequestStatus.EXECUTED && request.getStatus() != RequestStatus.REJECTED) {
            throw new ConflictException("Only EXECUTED or REJECTED requests can be closed (current: "
                    + request.getStatus() + ")");
        }

        // Close: requester (SELF scope, skip SoD) or admin (global scope)
        if (!caller.isAdmin()) {
            if (!caller.id().equals(request.getRequester().getId())) {
                throw new ForbiddenException("Only the requester or an admin can close this request");
            }
            // Requester closing own request: SELF-scoped permission, SoD doesn't apply
            TargetContext target = TargetContext.from(request, null);
            AuthorizationDecision d = authz.authorize(caller, "request:close:own", target, true);
            if (!d.allowed()) {
                throw new ForbiddenException("Not authorized to close: " + d.evidence());
            }
        }

        request.transitionTo(RequestStatus.CLOSED);
        metrics.counter("flowops.request.closed").increment();
        return requests.save(request);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void materializeFrom(Request request, List<WorkflowStep> all, int fromPosition) {
        for (WorkflowStep step : all) {
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

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
