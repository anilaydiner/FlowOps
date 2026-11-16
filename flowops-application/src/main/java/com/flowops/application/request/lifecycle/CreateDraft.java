package com.flowops.application.request.lifecycle;

import com.flowops.domain.CorrelationId;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.RequestValidationException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.workflow.RequestType;
import com.flowops.domain.workflow.policy.PayloadValidator;
import com.flowops.domain.workflow.port.RequestTypeRepository;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: create a DRAFT request for the caller.
 *
 * <p>Resolves and validates the request type, checks the payload against the
 * type's required fields and validation rules, then persists the new draft.
 * The caller is always the requester.</p>
 */
@Service
public class CreateDraft {

    private final RequestTypeRepository requestTypes;
    private final UserRepository users;
    private final RequestRepository requests;
    private final PayloadValidator validator;
    private final AuthorizationEvaluator authz;

    public CreateDraft(RequestTypeRepository requestTypes,
                       UserRepository users,
                       RequestRepository requests,
                       PayloadValidator validator,
                       AuthorizationEvaluator authz) {
        this.requestTypes = requestTypes;
        this.users = users;
        this.requests = requests;
        this.validator = validator;
        this.authz = authz;
    }

    @Transactional
    public Request execute(String typeCode, Map<String, Object> payload, CallerIdentity caller) {
        // 1 ── Resolve the request type first (needed for scope-aware authz)
        String resolvedCode = typeCode.strip().toUpperCase();
        RequestType type = requestTypes.findByCode(resolvedCode)
                .orElseThrow(() -> new NotFoundException("Request type not found: " + typeCode));
        if (!type.isActive()) {
            throw new ConflictException("Request type is inactive: " + type.getCode());
        }

        // 2 ── Scoped authorization: the target carries the type code so that
        //    REQUEST_TYPE-scoped permissions are evaluated against the specific
        //    type being requested (e.g. "can create EXPENSE but not IT_ACCESS").
        String deptCode = type.getOwningDepartment() != null
                ? type.getOwningDepartment().getCode() : null;
        TargetContext target = new TargetContext(null, resolvedCode, deptCode, null, null);
        authz.require(caller, PermissionCodes.REQUEST_CREATE, target);

        // 3 ── Load requester, validate payload, persist
        User requester = users.findById(caller.id())
                .orElseThrow(() -> new IllegalStateException("Caller not found: " + caller.id()));

        Map<String, String> violations = validator.validate(type, payload);
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }

        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        Request draft = Request.newDraft(type, requester, payload, correlationId);
        return requests.save(draft);
    }
}
