package com.flowops.application.request.query;

import com.flowops.application.mediator.Message;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;

public record GetApprovalQueue(PageQuery page, CallerIdentity caller)
        implements Message<PageResult<RequestListItem>> {
}
