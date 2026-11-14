package com.flowops.service;

import com.flowops.api.dto.RequestFilter;
import com.flowops.domain.Decision;
import com.flowops.domain.DecisionAction;
import com.flowops.domain.DecisionRepository;
import com.flowops.domain.Request;
import com.flowops.domain.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.RequestType;
import com.flowops.domain.RequestTypeRepository;
import com.flowops.domain.RequestSpecifications;
import com.flowops.domain.StepInstance;
import com.flowops.domain.StepInstanceRepository;
import com.flowops.domain.StepState;
import com.flowops.domain.User;
import com.flowops.domain.UserRepository;
import com.flowops.security.UserPrincipal;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request lifecycle: draft create/edit/cancel (iteration 3) plus
 * submit/decide/close through the workflow engine (iteration 4).
 */
@Service
public class RequestService {

    private final RequestRepository requests;
    private final RequestTypeRepository requestTypes;
    private final UserRepository users;
    private final PayloadValidator validator;
    private final WorkflowEngine engine;
    private final StepInstanceRepository stepInstances;
    private final DecisionRepository decisions;
    private final com.flowops.domain.ClarificationRepository clarifications;
    private final com.flowops.domain.ExecutionJobRepository executionJobs;

    public RequestService(RequestRepository requests,
                          RequestTypeRepository requestTypes,
                          UserRepository users,
                          PayloadValidator validator,
                          WorkflowEngine engine,
                          StepInstanceRepository stepInstances,
                          DecisionRepository decisions,
                          com.flowops.domain.ClarificationRepository clarifications,
                          com.flowops.domain.ExecutionJobRepository executionJobs) {
        this.requests = requests;
        this.requestTypes = requestTypes;
        this.users = users;
        this.validator = validator;
        this.engine = engine;
        this.stepInstances = stepInstances;
        this.decisions = decisions;
        this.clarifications = clarifications;
        this.executionJobs = executionJobs;
    }

    @Transactional
    public Request createDraft(String typeCode, Map<String, Object> payload, UserPrincipal caller) {
        RequestType type = requestTypes.findByCode(typeCode.strip().toUpperCase())
                .orElseThrow(() -> new NotFoundException("Request type not found: " + typeCode));
        if (!type.isActive()) {
            throw new ConflictException("Request type is inactive: " + type.getCode());
        }
        User requester = users.findById(caller.id())
                .orElseThrow(() -> new IllegalStateException("Caller not found: " + caller.id()));

        Map<String, String> violations = validator.validate(type, payload);
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }

        String correlationId = MDC.get(com.flowops.infra.CorrelationId.MDC_KEY);
        Request draft = Request.newDraft(type, requester, payload, correlationId);
        return ready(requests.save(draft));
    }

    @Transactional(readOnly = true)
    public Request get(Long id, UserPrincipal caller) {
        Request request = requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
        checkReadAccess(request, caller);
        return withDetails(request);
    }

    @Transactional(readOnly = true)
    public Page<Request> list(RequestFilter filter, Pageable pageable, UserPrincipal caller) {
        // Non-admins can only ever see their own requests
        Specification<Request> spec = RequestSpecifications.from(filter, caller.id(), caller.isAdmin());
        Page<Request> page = (spec == null) ? requests.findAll(pageable) : requests.findAll(spec, pageable);
        page.getContent().forEach(this::ready);
        return page;
    }

    @Transactional
    public Request updateDraft(Long id, Map<String, Object> newPayload, UserPrincipal caller) {
        Request request = requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
        if (!isOwnerOrAdmin(request, caller)) {
            // Same 404 as reads: do not leak existence of other users' requests
            throw new NotFoundException("Request not found: " + id);
        }
        if (!request.isDraft()) {
            throw new ConflictException("Only DRAFT requests can be edited (current: " + request.getStatus() + ")");
        }
        Map<String, String> violations = validator.validate(request.getType(), newPayload);
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
        request.replacePayload(newPayload);
        return ready(requests.save(request));
    }

    @Transactional
    public Request cancel(Long id, UserPrincipal caller) {
        Request request = requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
        if (!isOwnerOrAdmin(request, caller)) {
            // Same 404 as reads: do not leak existence of other users' requests
            throw new NotFoundException("Request not found: " + id);
        }
        if (request.getStatus().isTerminal()) {
            throw new ConflictException("Cannot cancel a request in terminal state: " + request.getStatus());
        }
        request.cancel();
        return ready(requests.save(request));
    }

    // ── workflow lifecycle (iteration 4) ───────────────────────────────

    /**
     * Submits a draft into the workflow: DRAFT → IN_REVIEW with the first
     * applicable step materialized (or APPROVED when every step is skipped).
     * Owner or admin only; non-owner gets 404 (no existence leak).
     */
    @Transactional
    public Request submit(Long id, UserPrincipal caller) {
        requireWriteAccess(id, caller);
        Request submitted = engine.submit(id, caller);
        return withDetails(submitted);
    }

    /**
     * Records an APPROVE/REJECT/DELEGATE decision on the current step.
     * SoD and role eligibility are enforced inside the engine.
     */
    @Transactional
    public Request decide(Long id, DecisionAction action, String justification,
                          Long delegatedTo, UserPrincipal caller) {
        Request decided = engine.decide(id, action, justification, caller, delegatedTo);
        return withDetails(decided);
    }

    /**
     * Closes an EXECUTED or REJECTED request (terminal).
     */
    @Transactional
    public Request close(Long id, UserPrincipal caller) {
        Request closed = engine.close(id, caller);
        return withDetails(closed);
    }

    // ── queue (approver inbox) ─────────────────────────────────────────

    /**
     * Returns IN_REVIEW requests where the caller is eligible for the current
     * (PENDING) step: the step's required role is in the caller's roles and
     * the caller is not the requester (SoD).
     */
    @Transactional(readOnly = true)
    public Page<Request> queue(Pageable pageable, UserPrincipal caller) {
        Set<String> roles = (caller.roles() != null) ? caller.roles() : Set.of();
        if (roles.isEmpty()) {
            return Page.empty(pageable);
        }
        List<Long> eligibleIds = stepInstances.findEligibleRequestIds(
                StepState.PENDING, RequestStatus.IN_REVIEW, roles, caller.id());
        if (eligibleIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Specification<Request> spec = (root, query, cb) ->
                cb.and(
                        root.get("id").in(eligibleIds),
                        cb.equal(root.get("status"), RequestStatus.IN_REVIEW));
        Page<Request> page = requests.findAll(spec, pageable);
        page.getContent().forEach(this::ready);
        return page;
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Request requireWriteAccess(Long id, UserPrincipal caller) {
        Request request = requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
        if (!isOwnerOrAdmin(request, caller)) {
            throw new NotFoundException("Request not found: " + id);
        }
        return request;
    }

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
     * lazy step/actor associations) while the session is open, so
     * {@code RequestResponse.from()} can map them without a session.
     */
    private Request withDetails(Request r) {
        ready(r);
        List<StepInstance> steps = stepInstances.findByRequestIdOrderById(r.getId());
        r.setLoadedSteps(steps);
        if (!steps.isEmpty()) {
            Map<Long, List<Decision>> byStep = new LinkedHashMap<>();
            for (StepInstance si : steps) {
                si.getStep().getCode(); // touch lazy step
                List<Decision> ds = decisions.findByStepInstanceIdOrderById(si.getId());
                ds.forEach(d -> d.getActorUsername()); // touch lazy actor
                byStep.put(si.getId(), ds);
            }
            r.setLoadedDecisions(byStep);
        }
        // Iteration 7: load clarifications + execution jobs
        r.setLoadedClarifications(clarifications.findByRequestIdOrderByCreatedAtDesc(r.getId()));
        r.setLoadedJobs(executionJobs.findByRequestIdOrderByCreatedAtAsc(r.getId()));
        return r;
    }

    private void checkReadAccess(Request request, UserPrincipal caller) {
        if (!isOwnerOrAdmin(request, caller)) {
            // Do not leak existence of other users' requests
            throw new NotFoundException("Request not found: " + request.getId());
        }
    }

    private boolean isOwnerOrAdmin(Request request, UserPrincipal caller) {
        return caller.isAdmin() || caller.id().equals(request.getRequester().getId());
    }
}
