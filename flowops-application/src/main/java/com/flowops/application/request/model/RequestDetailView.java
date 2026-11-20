package com.flowops.application.request.model;

import com.flowops.domain.execution.JobStatus;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.StepState;
import com.flowops.domain.workflow.CompletionRule;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Scalar request detail read model, independent of aggregate and persistence lifecycles.
 */
public record RequestDetailView(RequestListItem request, List<StepView> steps,
        Map<Long, List<DecisionView>> decisionsByStep, List<ClarificationView> clarifications, List<JobView> jobs) {
    public record StepView(Long id, int position, String code, String name, String requiredRole, StepState state,
            CompletionRule rule, int requiredApprovals, String skipReason, String blockedReason, Long satisfiedBy,
            Instant satisfiedAt, boolean rosterSnapshotted, Integer rosterSize,
            com.flowops.domain.request.BlockReason blockReasonCode, Instant blockedAt, int blockEpisode,
            Long recoveryOwnerId, String recoveryTeam, Instant recoveredAt, Instant activatedAt, Instant reviewDueAt,
            Instant nextEscalationAt, Instant expiresAt, int escalationLevel, int recoveryOwnerRevision,
            boolean recoveryOwnerUnavailable, int escalationIntervalMinutes, Integer maxEscalationLevels) {
    }

    public record DecisionView(Long id, Long stepInstanceId, Long actorId, String actorUsername, DecisionAction action,
            String justification, Map<String, Object> evidence, Long delegatedTo, Instant createdAt) {
    }

    public record ClarificationView(Long id, Long askedBy, String question, String answer, Long answeredBy,
            Instant answeredAt, Instant createdAt) {
    }

    public record JobView(Long id, String action, JobStatus status, Map<String, Object> result, String lastError,
            int retryCount, Instant createdAt, Instant completedAt, Instant startedAt, Instant cancelledAt,
            Long cancelledBy, int attemptCount, Instant leaseExpiresAt, boolean startInferred) {
    }
}
