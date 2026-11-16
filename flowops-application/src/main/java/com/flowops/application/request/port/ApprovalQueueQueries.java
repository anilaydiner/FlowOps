package com.flowops.application.request.port;

import com.flowops.application.request.model.RequestListItem;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import java.util.Set;

public interface ApprovalQueueQueries {
    PageResult<RequestListItem> findPage(PageQuery page, Long callerId, Set<String> roles);
}
