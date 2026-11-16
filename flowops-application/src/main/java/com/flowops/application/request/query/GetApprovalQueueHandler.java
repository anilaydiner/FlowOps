package com.flowops.application.request.query;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.ApprovalQueueQueries;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.paging.PageResult;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetApprovalQueueHandler implements MessageHandler<GetApprovalQueue, PageResult<RequestListItem>> {
    private final ApprovalQueueQueries queue;
    private final AuthorizationEvaluator authz;

    public GetApprovalQueueHandler(ApprovalQueueQueries queue, AuthorizationEvaluator authz) {
        this.queue = queue;
        this.authz = authz;
    }

    @Override
    public Class<GetApprovalQueue> messageType() {
        return GetApprovalQueue.class;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RequestListItem> handle(GetApprovalQueue query) {
        authz.require(query.caller(), PermissionCodes.REQUEST_QUEUE);
        Set<String> roles = query.caller().roles();
        if (roles.isEmpty()) {
            return PageResult.empty(query.page().page(), query.page().size());
        }
        return queue.findPage(query.page(), query.caller().id(), roles);
    }
}
