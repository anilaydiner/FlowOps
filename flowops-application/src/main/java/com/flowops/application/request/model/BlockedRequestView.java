package com.flowops.application.request.model;

import com.flowops.domain.request.BlockReason;
import java.time.Instant;

public record BlockedRequestView(RequestListItem request, BlockedStep block) {
    public record BlockedStep(Long requestId, Long stepInstanceId, String stepCode, BlockReason reason,
                              String detail, Instant blockedAt, int episode, Long ownerId, String team, int ownerRevision, boolean ownerUnavailable) {}
}
