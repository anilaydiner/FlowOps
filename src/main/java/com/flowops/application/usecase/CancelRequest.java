package com.flowops.application.usecase;

import com.flowops.domain.Request;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.port.RequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: cancel a request (any non-terminal state → CANCELLED).
 *
 * <p>Owner or admin only; non-owner gets 404 (no existence leak).</p>
 */
@Service
public class CancelRequest {

    private final RequestRepository requests;

    public CancelRequest(RequestRepository requests) {
        this.requests = requests;
    }

    @Transactional
    public Request execute(Long id, CallerIdentity caller) {
        Request request = load(id);
        if (!isOwnerOrAdmin(request, caller)) {
            // Same 404 as reads: do not leak existence of other users' requests
            throw new NotFoundException("Request not found: " + id);
        }
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

    private boolean isOwnerOrAdmin(Request request, CallerIdentity caller) {
        return caller.isAdmin() || caller.id().equals(request.getRequester().getId());
    }
}
