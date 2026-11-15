package com.flowops.application.service;

import com.flowops.application.model.RequestDetail;
import com.flowops.application.port.RequestLifecycleObservability;
import com.flowops.application.usecase.CancelRequest;
import com.flowops.application.usecase.CloseRequest;
import com.flowops.application.usecase.CreateDraft;
import com.flowops.application.usecase.DecideStep;
import com.flowops.application.usecase.EligibleDelegatees;
import com.flowops.application.usecase.SubmitRequest;
import com.flowops.application.usecase.UpdateDraft;
import com.flowops.domain.Decision;
import com.flowops.domain.User;
import com.flowops.domain.DecisionAction;
import com.flowops.domain.Clarification;
import com.flowops.domain.ExecutionJob;
import com.flowops.domain.Request;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.StepState;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.ClarificationRepository;
import com.flowops.domain.port.DecisionRepository;
import com.flowops.domain.port.ExecutionJobRepository;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.port.StepInstanceRepository;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;
import com.flowops.domain.policy.TargetContext;
import com.flowops.domain.query.RequestFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request lifecycle: draft create/edit/cancel (iteration 3) plus
 * submit/decide/close through the workflow engine (iteration 4).
 *
 * <p>Lifecycle observability (spans + counters, PLAN §8) goes through the
 * framework-free {@link RequestLifecycleObservability} port; the adapter in
 * {@code com.flowops.observability} emits the actual OpenTelemetry /
 * Micrometer signals.
 */
@Service
public class RequestService {

    private final RequestRepository requests;
    private final CreateDraft createDraft;
    private final UpdateDraft updateDraft;
    private final CancelRequest cancelRequest;
    private final SubmitRequest submitRequest;
    private final DecideStep decideStep;
    private final EligibleDelegatees eligibleDelegatees;
    private final CloseRequest closeRequest;
    private final RequestLifecycleObservability lifecycleObservability;
    private final StepInstanceRepository stepInstances;
    private final DecisionRepository decisions;
    private final ClarificationRepository clarifications;
    private final ExecutionJobRepository executionJobs;
    private final AuthorizationEvaluator authz;

    public RequestService(RequestRepository requests,
                          CreateDraft createDraft,
                          UpdateDraft updateDraft,
                          CancelRequest cancelRequest,
                          SubmitRequest submitRequest,
                          DecideStep decideStep,
                          EligibleDelegatees eligibleDelegatees,
                          CloseRequest closeRequest,
                          RequestLifecycleObservability lifecycleObservability,
                          StepInstanceRepository stepInstances,
                          DecisionRepository decisions,
                          ClarificationRepository clarifications,
                          ExecutionJobRepository executionJobs,
                          AuthorizationEvaluator authz) {
        this.requests = requests;
        this.createDraft = createDraft;
        this.updateDraft = updateDraft;
        this.cancelRequest = cancelRequest;
        this.submitRequest = submitRequest;
        this.decideStep = decideStep;
        this.eligibleDelegatees = eligibleDelegatees;
        this.closeRequest = closeRequest;
        this.lifecycleObservability = lifecycleObservability;
        this.stepInstances = stepInstances;
        this.decisions = decisions;
        this.clarifications = clarifications;
        this.executionJobs = executionJobs;
        this.authz = authz;
    }

    @Transactional
    public RequestDetail createDraft(String typeCode, Map<String, Object> payload, CallerIdentity caller) {
        return RequestDetail.of(ready(createDraft.execute(typeCode, payload, caller)));
    }

    @Transactional(readOnly = true)
    public RequestDetail get(Long id, CallerIdentity caller) {
        Request request = requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        TargetContext target = TargetContext.from(request, null);
        authz.require(caller, isOwn ? PermissionCodes.REQUEST_READ_OWN : PermissionCodes.REQUEST_READ, target, isOwn);
        return fullDetail(request);
    }

    @Transactional(readOnly = true)
    public PageResult<RequestDetail> list(RequestFilter filter, PageQuery query, CallerIdentity caller) {
        boolean canSeeAll = authz.hasPermission(caller, PermissionCodes.REQUEST_LIST);
        PageResult<Request> page = requests.findAll(filter, query, canSeeAll ? null : caller.id(), canSeeAll);
        return page.map(r -> RequestDetail.of(ready(r)));
    }

    @Transactional
    public RequestDetail updateDraft(Long id, Map<String, Object> newPayload, CallerIdentity caller) {
        return RequestDetail.of(ready(updateDraft.execute(id, newPayload, caller)));
    }

    @Transactional
    public RequestDetail cancel(Long id, CallerIdentity caller) {
        return RequestDetail.of(ready(cancelRequest.execute(id, caller)));
    }

    // ── workflow lifecycle (iteration 4) ───────────────────────────────

    /**
     * Submits a draft into the workflow: DRAFT → IN_REVIEW with the first
     * applicable step materialized (or APPROVED when every step is skipped).
     * Requires {@code request:submit:own} or {@code request:submit}.
     */
    @Transactional
    public RequestDetail submit(Long id, CallerIdentity caller) {
        Request submitted = lifecycleObservability.submit(id, caller.username(),
                () -> submitRequest.execute(id, caller));
        return fullDetail(submitted);
    }

    /**
     * Records an APPROVE/REJECT/DELEGATE decision on the current step.
     * SoD and role eligibility are enforced by the {@code DecideStep} use case.
     */
    @Transactional
    public RequestDetail decide(Long id, DecisionAction action, String justification,
                          Long delegatedTo, CallerIdentity caller) {
        Request decided = lifecycleObservability.decide(id, caller.username(), action,
                () -> decideStep.execute(id, action, justification, caller, delegatedTo));
        return fullDetail(decided);
    }

    /**
     * Lists the users the caller (current approver) may delegate this
     * request's decision to. Only users who would pass the same approval
     * gate are returned; the caller and the requester are excluded.
     */
    @Transactional(readOnly = true)
    public List<User> eligibleDelegatees(Long id, CallerIdentity caller) {
        return eligibleDelegatees.list(id, caller);
    }

    /**
     * Closes an EXECUTED or REJECTED request (terminal).
     * Requires {@code request:close:own} or {@code request:close}.
     */
    @Transactional
    public RequestDetail close(Long id, CallerIdentity caller) {
        Request closed = lifecycleObservability.close(id, caller.username(),
                () -> closeRequest.execute(id, caller));
        return fullDetail(closed);
    }

    // ── queue (approver inbox) ─────────────────────────────────────────

    /**
     * Returns IN_REVIEW requests where the caller is eligible for the current
     * (PENDING) step: the step's required role is in the caller's roles and
     * the caller is not the requester (SoD).
     */
    @Transactional(readOnly = true)
    public PageResult<RequestDetail> queue(PageQuery query, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.REQUEST_QUEUE);
        Set<String> roles = (caller.roles() != null) ? caller.roles() : Set.of();
        if (roles.isEmpty()) {
            return PageResult.empty(query.page(), query.size());
        }
        List<Long> eligibleIds = stepInstances.findEligibleRequestIds(
                StepState.PENDING, RequestStatus.IN_REVIEW, roles, caller.id());
        if (eligibleIds.isEmpty()) {
            return PageResult.empty(query.page(), query.size());
        }
        PageResult<Request> page = requests.findQueuePage(eligibleIds, query);
        return page.map(r -> RequestDetail.of(ready(r)));
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Initializes lazy associations (requester, owning department) while the
     * transaction/session is still open, so DTO mapping in the controller
     * cannot trigger a LazyInitializationException. List views keep this
     * light — step/decision detail is loaded only on the detail path.
     */
    private Request ready(Request r) {
        r.getRequester().getUsername();
        com.flowops.domain.Department dept = r.getType().getOwningDepartment();
        if (dept != null) {
            dept.getCode();
        }
        return r;
    }

    /**
     * Detail path: loads step instances + their decisions (and touches the
     * lazy step/actor associations) while the session is open, and assembles
     * them into a {@link RequestDetail} read model so
     * {@code RequestResponse.from()} can map them without a session.
     */
    private RequestDetail fullDetail(Request r) {
        ready(r);
        List<StepInstance> steps = stepInstances.findByRequestIdOrderById(r.getId());
        Map<Long, List<Decision>> byStep = new LinkedHashMap<>();
        for (StepInstance si : steps) {
            si.getStep().getCode(); // touch lazy step
            List<Decision> ds = decisions.findByStepInstanceIdOrderById(si.getId());
            ds.forEach(d -> d.getActorUsername()); // touch lazy actor
            byStep.put(si.getId(), ds);
        }
        // Iteration 7: load clarifications + execution jobs
        List<Clarification> clari = clarifications.findByRequestIdOrderByCreatedAtDesc(r.getId());
        List<ExecutionJob> jobs = executionJobs.findByRequestIdOrderByCreatedAtAsc(r.getId());
        return RequestDetail.full(r, steps, byStep, clari, jobs);
    }
}
