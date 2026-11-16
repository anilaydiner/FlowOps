package com.flowops.application.request.lifecycle;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.RequestValidationException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.workflow.policy.PayloadValidator;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: edit the payload of a DRAFT request.
 *
 * <p>Requires {@code request:update:own} for the requester or
 * {@code request:update} for others. The new payload must satisfy the
 * request type's validation rules.</p>
 */
@Service
public class UpdateDraft {

    private final RequestRepository requests;
    private final PayloadValidator validator;
    private final AuthorizationEvaluator authz;

    public UpdateDraft(RequestRepository requests, PayloadValidator validator, AuthorizationEvaluator authz) {
        this.requests = requests;
        this.validator = validator;
        this.authz = authz;
    }

    @Transactional
    public Request execute(Long id, Map<String, Object> newPayload, CallerIdentity caller) {
        Request request = load(id);
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        TargetContext target = TargetContext.from(request, null);
        authz.require(caller, isOwn ? PermissionCodes.REQUEST_UPDATE_OWN : PermissionCodes.REQUEST_UPDATE, target, isOwn);
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
}
