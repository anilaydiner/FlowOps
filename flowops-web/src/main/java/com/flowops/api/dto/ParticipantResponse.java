package com.flowops.api.dto;

import com.flowops.domain.request.StepParticipant;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record ParticipantResponse(UUID id, UUID slotId, Long stepInstanceId, Long userId, UUID predecessorId,
        Long decisionId, Instant assignedAt, boolean currentAssignment, UUID recoveryId) {
    public static List<ParticipantResponse> from(List<StepParticipant> history) {
        Set<UUID> replaced = history.stream()
            .map(StepParticipant::predecessorId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        return history.stream()
            .map(p -> new ParticipantResponse(p.id(), p.slotId(), p.stepInstanceId(), p.userId(), p.predecessorId(),
                    p.decisionId(), p.createdAt(), !replaced.contains(p.id()), p.recoveryId()))
            .toList();
    }
}
