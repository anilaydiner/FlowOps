package com.flowops.api.dto;

import com.flowops.application.execution.model.LegacyPlanDisposition;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LegacyPlanDispositionRequest(UUID commandId, Long expectedVersion, Mode mode, String reason,
        String evidenceReference, List<Action> actions) {
    public enum Mode {

        ADOPT, KEEP_QUARANTINED

    }

    public record Action(String action, Long existingJobId, String idempotencyKey, Map<String, Object> input,
            String providerId) {
        LegacyPlanDisposition.ProposedAction toProposedAction() {
            return new LegacyPlanDisposition.ProposedAction(action, existingJobId, idempotencyKey, input, providerId);
        }
    }

    public LegacyPlanDisposition.Input toInput() {
        var applicationMode = mode == null ? null : LegacyPlanDisposition.Mode.valueOf(mode.name());
        var proposed = actions == null ? null
                : actions.stream().map(action -> action == null ? null : action.toProposedAction()).toList();
        return new LegacyPlanDisposition.Input(commandId, expectedVersion, applicationMode, reason, evidenceReference,
                proposed);
    }
}
