package com.flowops.domain.execution;

import com.flowops.domain.request.ApprovalEvidence;
import java.time.Instant;
import java.util.*;

/** Immutable approval-time action manifest. Empty means deliberately no work; absent means unknown. */
public record ExecutionPlan(UUID id,Long requestId,Long typeId,String typeCode,Instant sourceTypeUpdatedAt,
                            Instant plannedAt,ApprovalEvidence approval,List<Action> actions) {
    public ExecutionPlan {
        Objects.requireNonNull(id);Objects.requireNonNull(requestId);Objects.requireNonNull(plannedAt);Objects.requireNonNull(approval);
        actions=List.copyOf(actions);
        if(actions.stream().map(Action::action).distinct().count()!=actions.size()) throw new IllegalArgumentException("Plan actions must be distinct");
    }
    public ApprovalEvidence.FrozenPlan approvalPlan() {
        return new ApprovalEvidence.FrozenPlan(id, requestId, plannedAt, approval);
    }

    public record Configuration(Long typeId,String typeCode,Instant updatedAt,List<String> actions) {}
    public record Action(int position,String action,String idempotencyKey,Map<String,Object> input,String correlationId) {
        public Action {
            if(position<1 || action==null || action.isBlank() || action.length()>64 || idempotencyKey==null)
                throw new IllegalArgumentException("Invalid planned action");
            input=ActionCommand.freeze(input);
        }
    }
}
