package com.flowops.application.request.lifecycle;

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

    private final RequestRepository requests;
    private final AuthorizationEvaluator authz;

    public CloseRequest(RequestRepository requests, AuthorizationEvaluator authz) {
        this.requests = requests;
        this.authz = authz;
    }

    @Transactional
    public Request execute(Long requestId, CallerIdentity caller) {
        Request request = load(requestId);
        if (request.getStatus() != RequestStatus.EXECUTED && request.getStatus() != RequestStatus.REJECTED) {
            throw new ConflictException("Only EXECUTED or REJECTED requests can be closed (current: "
                    + request.getStatus() + ")");
        }

        boolean isOwn = caller.id().equals(request.getRequester().getId());
        TargetContext target = TargetContext.from(request, null);
        authz.require(caller, isOwn ? PermissionCodes.REQUEST_CLOSE_OWN : PermissionCodes.REQUEST_CLOSE, target, isOwn);

        request.transitionTo(RequestStatus.CLOSED);
        return requests.save(request);
    }

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
