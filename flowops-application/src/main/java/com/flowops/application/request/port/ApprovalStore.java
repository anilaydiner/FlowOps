package com.flowops.application.request.port;

import com.flowops.domain.execution.ExecutionPlan;
import com.flowops.domain.request.Request;

public interface ApprovalStore {
    /** Persist the new approval and its frozen plan in the caller's transaction. */
    void saveApproval(Request request, ExecutionPlan plan);
}
