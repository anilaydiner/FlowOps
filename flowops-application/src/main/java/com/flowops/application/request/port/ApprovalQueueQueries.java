package com.flowops.application.request.port;

import com.flowops.application.request.model.RequestListItem;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Authority;

public interface ApprovalQueueQueries {
    PageResult<RequestListItem> findPage(PageQuery page, Authority authority);
}
