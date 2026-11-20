package com.flowops.application.request.lifecycle;

import com.flowops.application.request.RequestTargets;
import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.event.DomainEventPublisher;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.application.request.workflow.StepMaterializer;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.request.ApprovalEvidence;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.event.RequestSubmitted;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.workflow.Workflow;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: submit a DRAFT request into its default workflow.
 *
 * <p>DRAFT → IN_REVIEW with the first applicable step materialized (or APPROVED
 * when every step is skipped). Requires {@code request:submit:own} for the
 * requester or {@code request:submit} for others. On success, the submitter
 * is notified via the transactional outbox (FEED + EMAIL).</p>
 */
@Service
public class SubmitRequest {

    private final LifecycleAuditRecorder audit;

    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final StepMaterializer materializer;
    private final DomainEventPublisher events;
    private final AuthorizationEvaluator authz;
    private final FinalizeApproval finalization;
    private final java.time.Clock clock;

    public SubmitRequest(RequestRepository requests,
                         RequestWriteLock requestWriteLock,
                         StepMaterializer materializer,
                         DomainEventPublisher events,
                         AuthorizationEvaluator authz, LifecycleAuditRecorder audit,FinalizeApproval finalization,java.time.Clock clock) {
        this.audit = audit;this.finalization=finalization;this.clock=clock;
        this.requests = requests;
        this.requestWriteLock = requestWriteLock;
        this.materializer = materializer;
        this.events = events;
        this.authz = authz;
    }

    @Transactional
    public Request execute(Long requestId, CallerIdentity caller) {
        Request request = load(requestId);
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        TargetContext target = RequestTargets.from(request, null);
        authz.require(caller, isOwn ? PermissionCodes.REQUEST_SUBMIT_OWN : PermissionCodes.REQUEST_SUBMIT, target, isOwn);
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

        var beforeSubmit = LifecycleAuditRecorder.state(request);
        request.markSubmitted(clock.instant());
        requests.save(request);
        audit.transition(request, caller, beforeSubmit, null);
        var submittedState = LifecycleAuditRecorder.state(request);
        boolean active = materializer.materializeNext(request, workflow, caller);
        if (active) request.beginReview(clock.instant());
        Request saved = requests.save(request);
        audit.transition(saved, caller, submittedState, null);
        events.publish(new RequestSubmitted(saved.getId()));
        if (!active) {
            return finalization.execute(saved.getId(), ApprovalEvidence.allSkipped(caller.id(), caller.username()));
        }
        return requests.findById(saved.getId()).orElseThrow();
    }

    private Request load(Long id) {
        return requestWriteLock.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
