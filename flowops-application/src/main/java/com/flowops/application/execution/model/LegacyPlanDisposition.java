package com.flowops.application.execution.model;

import com.flowops.domain.execution.ActionCommand;
import java.time.Instant;
import java.util.*;

/**
 * Reviewed planning evidence, separate from the original approval and any provider
 * outcome.
 */
public final class LegacyPlanDisposition {

    private LegacyPlanDisposition() {
    }

    public enum Mode {

        ADOPT, KEEP_QUARANTINED

    }

    public record ProposedAction(String action, Long existingJobId, String idempotencyKey, Map<String, Object> input,
            String providerId) {
        public ProposedAction {
            if (input != null)
                input = ActionCommand.freeze(input);
        }
    }

    public record Input(UUID commandId, Long expectedVersion, Mode mode, String reason, String evidenceReference,
            List<ProposedAction> actions) {
        public Input {
            if (actions != null)
                actions = Collections.unmodifiableList(new ArrayList<>(actions));
        }
    }

    public record Entry(UUID commandId, Long requestId, UUID planId, Long actorId, String actorName,
            Long expectedVersion, Mode mode, String reason, String evidenceReference, Instant recordedAt,
            Map<String, Object> payload) {
        public Entry {
            payload = ActionCommand.freeze(payload);
        }
    }

    /**
     * No input payload or internal claim tokens are exposed through disposition receipts.
     */
    public record Receipt(UUID commandId, Long requestId, UUID planId, Long actorId, String actorName,
            Long expectedVersion, Mode mode, String reason, String evidenceReference, Instant recordedAt) {
        public static Receipt from(Entry e) {
            return new Receipt(e.commandId(), e.requestId(), e.planId(), e.actorId(), e.actorName(),
                    e.expectedVersion(), e.mode(), e.reason(), e.evidenceReference(), e.recordedAt());
        }
    }

    public record Result(Long requestVersion, Receipt disposition) {
    }

}
