package com.flowops.application.execution.model;

import com.flowops.domain.execution.ProviderObservation;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only commands and observations. Attempt identity fences delayed provider
 * responses.
 */
public final class ExecutionRecovery {

    private ExecutionRecovery() {
    }

    public enum Kind {

        RECONCILE, RETRY

    }

    public enum Disposition {

        OBSERVED, COMPLETED, RETRY_AUTHORIZED, PROVIDER_ERROR, STALE, AUTHORITY_REVOKED

    }

    public record Command(UUID id, Long sequence, Long requestId, Long jobId, Kind kind, Long actorId, String actorName,
            String reason, Long expectedVersion, Instant createdAt, Instant expiresAt, int attemptCount,
            UUID claimToken) {
    }

    public record Observation(UUID commandId, Instant recordedAt, Disposition disposition, ProviderObservation provider,
            String failureType) {
    }

    public record Entry(Command command, Observation observation) {
    }

}
