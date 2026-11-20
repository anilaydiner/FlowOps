package com.flowops.application.request.query;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.RequestListQueries;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.paging.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListRequestsHandler implements MessageHandler<ListRequests, PageResult<RequestListItem>> {

    private final RequestListQueries requests;

    private final AuthorizationEvaluator authz;

    public ListRequestsHandler(RequestListQueries requests, AuthorizationEvaluator authz) {
        this.requests = requests;
        this.authz = authz;
    }

    @Override
    public Class<ListRequests> messageType() {
        return ListRequests.class;
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<RequestListItem> handle(ListRequests query) {
        boolean canSeeAll = authz.hasPermission(query.caller(), PermissionCodes.REQUEST_LIST);
        return requests.findAll(query.filter(), query.page(), query.caller().id(), canSeeAll);
    }

}
