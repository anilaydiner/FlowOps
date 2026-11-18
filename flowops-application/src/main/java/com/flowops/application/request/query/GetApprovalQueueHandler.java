package com.flowops.application.request.query;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.ApprovalQueueQueries;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.paging.PageResult;
import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.domain.request.DecisionAction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetApprovalQueueHandler implements MessageHandler<GetApprovalQueue, PageResult<RequestListItem>> {
    private final ApprovalQueueQueries queue;
    private final AuthorizationEvaluator authz;
    private final ApprovalEligibilityService eligibility;

    public GetApprovalQueueHandler(ApprovalQueueQueries queue, AuthorizationEvaluator authz, ApprovalEligibilityService eligibility) {
        this.queue = queue;
        this.authz = authz;
        this.eligibility = eligibility;
    }

    @Override
    public Class<GetApprovalQueue> messageType() {
        return GetApprovalQueue.class;
    }

    @Override
    @Transactional(readOnly = true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<RequestListItem> handle(GetApprovalQueue query) {
        authz.require(query.caller(), PermissionCodes.REQUEST_QUEUE);
        return queue.findPage(query.page(), eligibility.authority(query.caller().id(), DecisionAction.APPROVE));
    }
}
