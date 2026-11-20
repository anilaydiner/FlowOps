package com.flowops.domain.request.event;

import com.flowops.domain.event.DomainEvent;
import com.flowops.domain.request.ApprovalEvidence;
import java.util.UUID;

/**
 * One frozen approval fact, published only after its plan/jobs are stored in the same
 * transaction.
 */
public record RequestApproved(Long requestId, UUID executionPlanId, ApprovalEvidence approval) implements DomainEvent {
    public RequestApproved {
        if (approval.trigger() == ApprovalEvidence.Trigger.LEGACY_PLAN_DISPOSITION)
            throw new IllegalArgumentException("Legacy plan adoption is not a new approval fact");
    }
}
