package com.flowops.application.usecase;

import com.flowops.domain.identity.CallerIdentity;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.Request;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.Workflow;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.application.notification.EmailComposer;
import com.flowops.domain.OutboxEvents;
import com.flowops.application.notification.OutboxService;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;
import com.flowops.domain.policy.TargetContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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

    private final RequestRepository requests;
    private final StepMaterializer materializer;
    private final OutboxService outbox;
    private final EmailComposer emailComposer;
    private final AuthorizationEvaluator authz;
    private final ApproverNotifier approverNotifier;

    public SubmitRequest(RequestRepository requests,
                         StepMaterializer materializer,
                         OutboxService outbox,
                         EmailComposer emailComposer,
                         AuthorizationEvaluator authz,
                         ApproverNotifier approverNotifier) {
        this.requests = requests;
        this.materializer = materializer;
        this.outbox = outbox;
        this.emailComposer = emailComposer;
        this.authz = authz;
        this.approverNotifier = approverNotifier;
    }

    @Transactional
    public Request execute(Long requestId, CallerIdentity caller) {
        Request request = load(requestId);
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        TargetContext target = TargetContext.from(request, null);
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

        request.markSubmitted(Instant.now());
        materializer.materializeNext(request, workflow, 0);
        if (request.getStatus() != RequestStatus.APPROVED) {
            request.transitionTo(RequestStatus.IN_REVIEW);
        }
        Request saved = requests.save(request);
        requests.flush(); // commit the state transition before touching the outbox

        // ── Outbox trigger: notify on submit (PLAN §8.1) ──────────────────
        Long requesterId = request.getRequester().getId();
        EmailComposer.ComposedEmail submitEmail = emailComposer.submitted(request);
        outbox.notifyFeed(requestId, requesterId, OutboxEvents.NOTIFY_SUBMIT,
                submitEmail.subject(), submitEmail.text(), null);
        outbox.notifyEmail(requestId, requesterId, OutboxEvents.SUBMITTED,
                submitEmail.subject(), submitEmail.text(), submitEmail.html(), null);

        // ── Phase 8: notify every eligible approver of the new request ─────
        if (request.getStatus() == RequestStatus.IN_REVIEW) {
            approverNotifier.notifyEligibleApprovers(request, null);
        }

        return saved;
    }

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
