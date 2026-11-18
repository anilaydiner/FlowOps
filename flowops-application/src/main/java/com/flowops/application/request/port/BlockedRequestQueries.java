package com.flowops.application.request.port;

import com.flowops.application.request.model.BlockedRequestView;
import com.flowops.domain.paging.*;
import com.flowops.domain.request.BlockReason;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Authority;
import java.time.Instant;

public interface BlockedRequestQueries {
    record Filter(BlockReason reason, Instant blockedBefore, Long ownerId, String team) {}
    PageResult<BlockedRequestView> findPage(PageQuery page, Filter filter, Authority authority);
}
