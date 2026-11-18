package com.flowops.application.execution.model;

import com.flowops.domain.execution.ActionCommand;
import java.time.Instant;
import java.util.*;

/** Immutable durable start authorization. A first-ever job start is not an individual attempt start. */
public record ExecutionAttempt(UUID token, Long requestId, Long jobId, int number,
                               String idempotencyKey, String action, Map<String,Object> input, String correlationId,
                               Instant authorizedAt, Instant initialLeaseExpiresAt, Instant deadline) {
    public ExecutionAttempt { input=ActionCommand.freeze(input); }

    public enum Kind { HEARTBEAT, OUTCOME, HOLD, PROVIDER_OBSERVATION, RETRY_AUTHORIZED }
    public record Event(UUID id, Long sequence, Long requestId, Long jobId, UUID attemptToken, Integer attemptNumber, Long baselineJobId,
                        Kind kind, Instant at, Instant leaseExpiresAt, String outcome,
                        Map<String,Object> details, UUID recoveryCommandId) {
        public Event { details=ActionCommand.freeze(details); }
    }
    /** A labeled observation of available state, never a reconstructed attempt. */
    public record Baseline(Long jobId, Long requestId, Instant observedAt, String status, int attemptCount,
                           Instant firstStartedAt, boolean startInferred, UUID claimToken, Instant observedLeaseExpiresAt) {}
}
