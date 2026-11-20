package com.flowops.api.dto;

import com.flowops.application.execution.model.LegacyPlanDisposition;
import java.time.Instant;
import java.util.UUID;

public record LegacyPlanDispositionResponse(Long requestVersion, Receipt disposition) {
    public record Receipt(UUID commandId, Long requestId, UUID planId, Long actorId, String actorName,
            Long expectedVersion, LegacyPlanDispositionRequest.Mode mode, String reason, String evidenceReference,
            Instant recordedAt) {
        public static Receipt from(LegacyPlanDisposition.Receipt receipt) {
            return new Receipt(receipt.commandId(), receipt.requestId(), receipt.planId(), receipt.actorId(),
                    receipt.actorName(), receipt.expectedVersion(),
                    LegacyPlanDispositionRequest.Mode.valueOf(receipt.mode().name()), receipt.reason(),
                    receipt.evidenceReference(), receipt.recordedAt());
        }
    }

    public static LegacyPlanDispositionResponse from(LegacyPlanDisposition.Result result) {
        return new LegacyPlanDispositionResponse(result.requestVersion(), Receipt.from(result.disposition()));
    }
}
