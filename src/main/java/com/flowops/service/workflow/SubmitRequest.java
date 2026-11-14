package com.flowops.service.workflow;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.Request;
import com.flowops.domain.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.Workflow;
import com.flowops.security.UserPrincipal;
import com.flowops.service.EmailComposer;
import com.flowops.service.OutboxEvents;
import com.flowops.service.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Use case: submit a DRAFT request into its default workflow.
 *
 * <p>DRAFT → IN_REVIEW with the first applicable step materialized (or APPROVED
 * when every step is skipped). On success, the submitter is notified via the
 * transactional outbox (FEED + EMAIL).</p>
 */
@Service
public class SubmitRequest {

    private final RequestRepository requests;
    private final StepMaterializer materializer;
    private final OutboxService outbox;
    private final EmailComposer emailComposer;

    public SubmitRequest(RequestRepository requests,
                         StepMaterializer materializer,
                         OutboxService outbox,
                         EmailComposer emailComposer) {
        this.requests = requests;
        this.materializer = materializer;
        this.outbox = outbox;
        this.emailComposer = emailComposer;
    }

    @Transactional
    public Request execute(Long requestId, UserPrincipal caller) {
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

        return saved;
    }

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
