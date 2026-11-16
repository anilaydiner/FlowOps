package com.flowops.application.request.query;

import com.flowops.application.mediator.MessageHandler;
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

    public GetRequestHandler(RequestDetailQueries details, AuthorizationEvaluator authz) {
        this.authz = authz;
        this.details = details;
    }

    @Override
    public Class<GetRequest> messageType() {
        return GetRequest.class;
    }

    @Override
    @Transactional(readOnly = true)
    public RequestDetailView handle(GetRequest query) {
        RequestListItem request = details.findRequest(query.id())
                .orElseThrow(() -> new NotFoundException("Request not found: " + query.id()));
        boolean isOwn = query.caller().id().equals(request.requesterId());
        TargetContext target = TargetContext.forRequest(request.requesterId(), request.typeCode(), request.departmentCode(), request.payload());
        authz.require(query.caller(), isOwn ? PermissionCodes.REQUEST_READ_OWN : PermissionCodes.REQUEST_READ,
                target, isOwn);
        return details.load(request);
    }
}
