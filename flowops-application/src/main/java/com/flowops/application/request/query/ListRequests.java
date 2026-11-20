package com.flowops.application.request.query;

import com.flowops.application.mediator.Message;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.request.query.RequestFilter;

public record ListRequests(RequestFilter filter, PageQuery page,
        CallerIdentity caller) implements Message<PageResult<RequestListItem>> {
}
