package com.flowops.domain.request;

import java.time.Instant;
import java.util.UUID;

/** Immutable assignment in an approval slot. Delegation appends a successor assignment. */
public record StepParticipant(UUID id, UUID slotId, Long stepInstanceId, Long userId,
                              UUID predecessorId, Long decisionId, Instant createdAt, UUID recoveryId) {
    public StepParticipant(UUID id, UUID slotId, Long stepInstanceId, Long userId,
                           UUID predecessorId, Long decisionId, Instant createdAt) {
        this(id, slotId, stepInstanceId, userId, predecessorId, decisionId, createdAt, null);
    }
}
