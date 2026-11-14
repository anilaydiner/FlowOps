package com.flowops.service.workflow;

import com.flowops.authz.AuthorizationDecision;
import com.flowops.authz.AuthorizationEvaluator;
import com.flowops.authz.PermissionCodes;
import com.flowops.authz.TargetContext;
import com.flowops.domain.Request;
import com.flowops.domain.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: close an EXECUTED or REJECTED request (terminal CLOSED state).
 *
 * <p>Allowed for the requester (SELF scope, SoD does not apply) or an admin
 * (global scope, no per-permission check).</p>
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
    public Request execute(Long requestId, UserPrincipal caller) {
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
            AuthorizationDecision d = authz.authorize(caller, PermissionCodes.REQUEST_CLOSE_OWN, target, true);
            if (!d.allowed()) {
                throw new ForbiddenException("Not authorized to close: " + d.evidence());
            }
        }

        request.transitionTo(RequestStatus.CLOSED);
        return requests.save(request);
    }

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
