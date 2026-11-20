package com.flowops.domain.request.policy;

import com.flowops.domain.request.BlockReason;
import com.flowops.domain.workflow.CompletionRule;

/**
 * Existing effective votes remain valid; only distinct future voters can close the gap.
 */
public final class ApprovalReachability {

    private ApprovalReachability() {
    }

    public static BlockReason blockReason(CompletionRule rule, int required, long votes, long remaining) {
        if (required < 1 || votes < 0 || remaining < 0)
            throw new IllegalArgumentException("Invalid quorum facts");
        if (votes + remaining >= required)
            return null;
        if (rule == CompletionRule.ALL_OF && votes + remaining > 0)
            return BlockReason.PARTICIPANT_UNAVAILABLE;
        if (votes == 0 && remaining == 0)
            return BlockReason.NO_ELIGIBLE_APPROVER;
        return rule == CompletionRule.ALL_OF ? BlockReason.PARTICIPANT_UNAVAILABLE : BlockReason.INSUFFICIENT_QUORUM;
    }

}
