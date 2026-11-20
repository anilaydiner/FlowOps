package com.flowops.domain.request;

import java.time.Instant;
import java.util.UUID;

/** Immutable operator recovery authorization, committed with the restored step. */
public record StepRecovery(UUID id, Long stepInstanceId, int blockEpisode, Long actorId, String reason,
        Instant createdAt) {
}
