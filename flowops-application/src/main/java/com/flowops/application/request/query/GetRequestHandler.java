package com.flowops.application.request.query;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.request.ParticipantRequestAccess;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.application.request.model.RequestDetailView;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.RequestDetailQueries;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetRequestHandler implements MessageHandler<GetRequest, RequestDetailView> {

    private final AuthorizationEvaluator authz;
    private final RequestDetailQueries details;
    private final ParticipantRequestAccess participants;
    private final com.flowops.application.request.recovery.BlockedRequestAccess blocked;

    public GetRequestHandler(RequestDetailQueries details, AuthorizationEvaluator authz, ParticipantRequestAccess participants,
                             com.flowops.application.request.recovery.BlockedRequestAccess blocked) {
        this.blocked = blocked;
        this.authz = authz;
        this.details = details;
        this.participants = participants;
    }

    @Override
    public Class<GetRequest> messageType() {
        return GetRequest.class;
    }

    @Override
    @Transactional(readOnly = true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RequestDetailView handle(GetRequest query) {
        RequestListItem request = details.findRequest(query.id())
                .orElseThrow(() -> new NotFoundException("Request not found: " + query.id()));
        boolean isOwn = query.caller().id().equals(request.requesterId());
        TargetContext target = TargetContext.forRequest(request.requesterId(), request.typeCode(), request.departmentCode(), request.payload());
        try {
            authz.require(query.caller(), isOwn ? PermissionCodes.REQUEST_READ_OWN : PermissionCodes.REQUEST_READ, target, isOwn);
        } catch (ForbiddenException denied) {
            if (request.status() == com.flowops.domain.request.RequestStatus.IN_REVIEW
                    && blocked.canRead(request.id(), query.caller(), target)) return details.load(request);
            if (isOwn || request.status() != com.flowops.domain.request.RequestStatus.IN_REVIEW
                    || !participants.canRead(request.id(), query.caller())) throw denied;
        }
        return details.load(request);
    }
}
