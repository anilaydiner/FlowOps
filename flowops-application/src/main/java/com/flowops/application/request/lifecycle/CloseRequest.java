package com.flowops.application.request.lifecycle;

import com.flowops.application.request.RequestTargets;
import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.port.RequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: close an EXECUTED or REJECTED request (terminal CLOSED state).
 *
 * <p>Requires {@code request:close:own} for the requester (SELF scope, SoD
 * skipped) or {@code request:close} for others (global scope).</p>
 */
@Service
public class CloseRequest {

    private final LifecycleAuditRecorder audit;

    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final AuthorizationEvaluator authz;
    private final java.time.Clock clock;

    public CloseRequest(RequestRepository requests, RequestWriteLock requestWriteLock, AuthorizationEvaluator authz, LifecycleAuditRecorder audit,java.time.Clock clock) {
        this.audit = audit;
        this.requests = requests;
        this.requestWriteLock = requestWriteLock;
        this.authz = authz;
        this.clock = clock;
    }

    @Transactional
    public Request execute(Long requestId, CallerIdentity caller) {
        Request request = load(requestId);
        if (!request.getStatus().canClose()) {
            throw new ConflictException("Only EXECUTED or REJECTED requests can be closed (current: "
                    + request.getStatus() + ")");
        }

        boolean isOwn = caller.id().equals(request.getRequester().getId());
        TargetContext target = RequestTargets.from(request, null);
        authz.require(caller, isOwn ? PermissionCodes.REQUEST_CLOSE_OWN : PermissionCodes.REQUEST_CLOSE, target, isOwn);

        var before = LifecycleAuditRecorder.state(request);
        request.close(clock.instant());
        Request saved = requests.save(request);
        audit.transition(saved, caller, before, null);
        return saved;
    }

    private Request load(Long id) {
        return requestWriteLock.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
