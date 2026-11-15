package com.flowops.application.usecase;

import com.flowops.domain.Request;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.RequestValidationException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.service.PayloadValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Use case: edit the payload of a DRAFT request.
 *
 * <p>Owner or admin only; non-owner gets 404 (no existence leak).
 * The new payload must satisfy the request type's validation rules.</p>
 */
@Service
public class UpdateDraft {

    private final RequestRepository requests;
    private final PayloadValidator validator;

    public UpdateDraft(RequestRepository requests, PayloadValidator validator) {
        this.requests = requests;
        this.validator = validator;
    }

    @Transactional
    public Request execute(Long id, Map<String, Object> newPayload, CallerIdentity caller) {
        Request request = load(id);
        if (!isOwnerOrAdmin(request, caller)) {
            // Same 404 as reads: do not leak existence of other users' requests
            throw new NotFoundException("Request not found: " + id);
        }
        if (!request.isDraft()) {
            throw new ConflictException("Only DRAFT requests can be edited (current: " + request.getStatus() + ")");
        }
        Map<String, String> violations = validator.validate(request.getType(), newPayload);
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
        request.replacePayload(newPayload);
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
