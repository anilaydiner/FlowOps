package com.flowops.application.request.port;

import com.flowops.application.request.model.RequestListItem;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.request.query.RequestFilter;

public interface RequestListQueries {
    /** Apply visibility and filters before paging; callerId is required even with list-all permission. */
    PageResult<RequestListItem> findAll(RequestFilter filter, PageQuery page, Long callerId, boolean canSeeAll);
}
