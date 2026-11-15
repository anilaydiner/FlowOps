package com.flowops.application.usecase;

import com.flowops.domain.Request;
import com.flowops.domain.RequestType;
import com.flowops.domain.User;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.RequestValidationException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.port.RequestTypeRepository;
import com.flowops.domain.port.UserRepository;
import com.flowops.domain.service.PayloadValidator;
import com.flowops.observability.CorrelationId;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

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

    public CreateDraft(RequestTypeRepository requestTypes,
                       UserRepository users,
                       RequestRepository requests,
                       PayloadValidator validator) {
        this.requestTypes = requestTypes;
        this.users = users;
        this.requests = requests;
        this.validator = validator;
    }

    @Transactional
    public Request execute(String typeCode, Map<String, Object> payload, CallerIdentity caller) {
        RequestType type = requestTypes.findByCode(typeCode.strip().toUpperCase())
                .orElseThrow(() -> new NotFoundException("Request type not found: " + typeCode));
        if (!type.isActive()) {
            throw new ConflictException("Request type is inactive: " + type.getCode());
        }
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
