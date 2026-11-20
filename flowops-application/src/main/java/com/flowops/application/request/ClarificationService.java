package com.flowops.application.request;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.request.Clarification;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.port.ClarificationRepository;
import com.flowops.domain.request.port.RequestRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clarification Q&A on a request (PLAN §8, iteration 7).
 *
 * <p>Access rules (consistent with the rest of the request API):
 * <ul>
 *   <li><b>Ask</b>: requester or admin, on a non-terminal request</li>
 *   <li><b>Answer</b>: requester or admin, on an unanswered clarification</li>
 *   <li><b>List</b>: requester or admin</li>
 * </ul>
 */
@Service
public class ClarificationService {

    private final LifecycleAuditRecorder audit;

    private final ClarificationRepository clarifications;
    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final AuthorizationEvaluator authz;

    public ClarificationService(ClarificationRepository clarifications,
                                RequestRepository requests,
                                RequestWriteLock requestWriteLock,
                                AuthorizationEvaluator authz, LifecycleAuditRecorder audit) {
        this.audit = audit;
        this.clarifications = clarifications;
        this.requests = requests;
        this.requestWriteLock = requestWriteLock;
        this.authz = authz;
    }

    @Transactional
    public Clarification ask(Long requestId, String question, CallerIdentity caller) {
        Request request = loadRequestForUpdate(requestId);
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        authz.require(caller, isOwn ? PermissionCodes.CLARIFICATIONS_ASK_OWN : PermissionCodes.CLARIFICATIONS_ASK);
        if (request.getStatus().isFinalized()) {
            throw new ConflictException("Cannot add clarifications to a terminal request: " + request.getStatus());
        }
        Clarification c = new Clarification(request.getId(), caller.id(), question);
        request.recordActivity();
        requests.save(request);
        Clarification saved = clarifications.save(c);
        audit.record(request, caller, "CLARIFICATION", "ASKED", "clarification:" + saved.getId() + ":asked",
                "Clarification requested", java.util.Map.of(), java.util.Map.of("answered", false),
                java.util.Map.of("clarificationId", saved.getId()), null);
        return saved;
    }

    @Transactional
    public Clarification answer(Long clarificationId, Long requestId, String answer, CallerIdentity caller) {
        Request request = loadRequestForUpdate(requestId);
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        authz.require(caller, isOwn ? PermissionCodes.CLARIFICATIONS_ANSWER_OWN : PermissionCodes.CLARIFICATIONS_ANSWER);
        if (request.getStatus().isFinalized()) {
            throw new ConflictException("Cannot answer clarifications on a terminal request: " + request.getStatus());
        }
        Clarification c = clarifications.findByIdAndRequestId(clarificationId, requestId)
                .orElseThrow(() -> new NotFoundException("Clarification not found: " + clarificationId));
        if (c.isAnswered()) {
            throw new ConflictException("Clarification already answered");
        }
        c.answer(answer, caller.id());
        request.recordActivity();
        requests.save(request);
        Clarification saved = clarifications.save(c);
        audit.record(request, caller, "CLARIFICATION", "ANSWERED", "clarification:" + saved.getId() + ":answered",
                "Clarification answered", java.util.Map.of("answered", false), java.util.Map.of("answered", true),
                java.util.Map.of("clarificationId", saved.getId()), null);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Clarification> listByRequest(Long requestId, CallerIdentity caller) {
        Request request = loadRequest(requestId);
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        authz.require(caller, isOwn ? PermissionCodes.CLARIFICATIONS_READ_OWN : PermissionCodes.CLARIFICATIONS_READ);
        return clarifications.findByRequestIdOrderByCreatedAtDesc(request.getId());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Request loadRequestForUpdate(Long requestId) {
        return requestWriteLock.findByIdForUpdate(requestId).orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
    }

    private Request loadRequest(Long requestId) {
        return requests.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
    }
}
