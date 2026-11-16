package com.flowops.application.usecase;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.Request;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.Workflow;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.application.event.DomainEventPublisher;
import com.flowops.domain.event.RequestSubmitted;
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
    private final DomainEventPublisher events;
    private final AuthorizationEvaluator authz;

    public SubmitRequest(RequestRepository requests,
                         StepMaterializer materializer,
                         DomainEventPublisher events,
                         AuthorizationEvaluator authz) {
        this.requests = requests;
        this.materializer = materializer;
        this.events = events;
        this.authz = authz;
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
        requests.flush(); // surface optimistic-lock conflicts before event subscribers write
        events.publish(new RequestSubmitted(saved.getId()));

        return saved;
    }

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
