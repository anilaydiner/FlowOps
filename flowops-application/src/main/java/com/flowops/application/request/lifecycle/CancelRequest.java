package com.flowops.application.request.lifecycle;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.port.RequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: cancel a request (any non-terminal state → CANCELLED).
 *
 * <p>Requires {@code request:cancel:own} for the requester or
 * {@code request:cancel} for others.</p>
 */
@Service
public class CancelRequest {

    private final RequestRepository requests;
    private final AuthorizationEvaluator authz;

    public CancelRequest(RequestRepository requests, AuthorizationEvaluator authz) {
        this.requests = requests;
        this.authz = authz;
    }

    @Transactional
    public Request execute(Long id, CallerIdentity caller) {
        Request request = load(id);
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        TargetContext target = TargetContext.from(request, null);
        authz.require(caller, isOwn ? PermissionCodes.REQUEST_CANCEL_OWN : PermissionCodes.REQUEST_CANCEL, target, isOwn);
        if (request.getStatus().isTerminal()) {
            throw new ConflictException("Cannot cancel a request in terminal state: " + request.getStatus());
        }
        request.cancel();
        return requests.save(request);
    }

    private Request load(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }
}
