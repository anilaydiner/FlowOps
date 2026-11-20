package com.flowops.api.dto;

import com.flowops.application.request.model.RequestDetail;
import com.flowops.application.request.model.RequestDetailView;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.domain.organization.Department;
import com.flowops.domain.request.Decision;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.StepInstance;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RequestResponse(
        Long id,
        String typeCode,
        String typeName,
        String departmentCode,
        String status,
        Map<String, Object> payload,
        long version,
        RequesterDto requester,
        String correlationId,
        List<StepDto> steps,
        List<ClarificationDto> clarifications,
        List<ExecutionJobDto> executionJobs,
        Instant createdAt,
        Instant updatedAt,
        Instant submittedAt,
        Instant executionPlannedAt,
        Instant executionStartedAt,
        boolean executionStartUnknown,
        boolean executionStartInferred,
        java.util.UUID executionPlanId,
        com.flowops.domain.request.RequestOutcome outcome,
        Instant approvedAt,Instant rejectedAt,Instant executedAt,Instant cancelledAt,Instant expiredAt,Instant closedAt,
        com.flowops.application.request.model.ExecutionProgress.State executionState,
        com.flowops.application.request.model.ExecutionProgress executionProgress,
        boolean requiresAttention
) {
    public static RequestResponse from(RequestDetail detail) {
        Request r = detail.request();
        com.flowops.domain.organization.Department dept = r.getType().getOwningDepartment();
        List<StepDto> stepDtos = detail.steps().stream().map(si -> toStepDto(si, detail)).toList();

        List<ClarificationDto> clariDtos = detail.clarifications().stream().map(c -> new ClarificationDto(
                c.getId(), c.getAskedBy(), c.getQuestion(),
                c.getAnswer(), c.getAnsweredBy(), c.getAnsweredAt(), c.getCreatedAt()
        )).toList();

        List<ExecutionJobDto> jobDtos = detail.jobs().stream().map(j -> new ExecutionJobDto(
                j.getId(), j.getAction(), j.getStatus().name(),
                j.getResult(), j.getLastError(), j.getRetryCount(),
                j.getCreatedAt(), j.getCompletedAt(),j.getStartedAt(),j.getCancelledAt(),j.getCancelledBy(),j.getAttemptCount(),j.getLeaseExpiresAt(),j.isStartInferred()
        )).toList();

        return new RequestResponse(
                r.getId(),
                r.getType().getCode(),
                r.getType().getName(),
                dept != null ? dept.getCode() : null,
                r.getStatus().name(),
                r.getPayload(),
                r.getVersion() != null ? r.getVersion() : 0L,
                new RequesterDto(
                        r.getRequester().getId(),
                        r.getRequester().getUsername(),
                        r.getRequester().getDisplayName()),
                r.getCorrelationId(),
                stepDtos,
                clariDtos,
                jobDtos,
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getSubmittedAt(),r.getExecutionPlannedAt(),r.getExecutionStartedAt(),r.isExecutionStartUnknown(),r.isExecutionStartInferred(),r.getExecutionPlanId(),r.getOutcome(),
                r.getApprovedAt(),r.getRejectedAt(),r.getExecutedAt(),r.getCancelledAt(),r.getExpiredAt(),r.getClosedAt(),
                detail.executionProgress().state(),detail.executionProgress(),detail.executionProgress().requiresAttention());
    }

    public static RequestResponse from(RequestListItem item) {
        return new RequestResponse(item.id(), item.typeCode(), item.typeName(), item.departmentCode(),
                item.status().name(), item.payload(), item.version() != null ? item.version() : 0L,
                new RequesterDto(item.requesterId(), item.requesterUsername(), item.requesterDisplayName()),
                item.correlationId(), List.of(), List.of(), List.of(),
                item.createdAt(), item.updatedAt(), item.submittedAt(),item.executionPlannedAt(),item.executionStartedAt(),item.executionStartUnknown(),item.executionStartInferred(),item.executionPlanId(),item.outcome(),
                item.approvedAt(),item.rejectedAt(),item.executedAt(),item.cancelledAt(),item.expiredAt(),item.closedAt(),
                item.executionProgress().state(),item.executionProgress(),item.executionProgress().requiresAttention());
    }

    public static RequestResponse from(RequestDetailView detail) {
        RequestListItem item = detail.request();
        return new RequestResponse(item.id(), item.typeCode(), item.typeName(), item.departmentCode(),
                item.status().name(), item.payload(), item.version() != null ? item.version() : 0L,
                new RequesterDto(item.requesterId(), item.requesterUsername(), item.requesterDisplayName()),
                item.correlationId(),
                detail.steps().stream().map(step -> new StepDto(
                        step.id(), step.position(), step.code(), step.name(), step.requiredRole(),
                        step.state().name(), step.rule().name(), step.requiredApprovals(), step.skipReason(),
                        step.blockedReason(), step.satisfiedBy(), step.satisfiedAt(),
                        detail.decisionsByStep().getOrDefault(step.id(), List.of()).stream()
                                .map(decision -> new DecisionDto(decision.id(), decision.actorId(), decision.actorUsername(),
                                        decision.action().name(), decision.justification(), decision.evidence(),
                                        decision.delegatedTo(), decision.createdAt())).toList(), step.rosterSnapshotted(), step.rosterSize(), step.blockReasonCode(), step.blockedAt(), step.blockEpisode(),
                        step.recoveryOwnerId(), step.recoveryTeam(), step.recoveredAt(), step.activatedAt(), step.reviewDueAt(),
                        step.nextEscalationAt(), step.expiresAt(), step.escalationLevel(), step.recoveryOwnerRevision(), step.recoveryOwnerUnavailable(), step.escalationIntervalMinutes(), step.maxEscalationLevels())).toList(),
                detail.clarifications().stream().map(clarification -> new ClarificationDto(
                        clarification.id(), clarification.askedBy(), clarification.question(), clarification.answer(),
                        clarification.answeredBy(), clarification.answeredAt(), clarification.createdAt())).toList(),
                detail.jobs().stream().map(job -> new ExecutionJobDto(job.id(), job.action(), job.status().name(),
                        job.result(), job.lastError(), job.retryCount(), job.createdAt(), job.completedAt(),job.startedAt(),job.cancelledAt(),job.cancelledBy(),job.attemptCount(),job.leaseExpiresAt(),job.startInferred())).toList(),
                item.createdAt(), item.updatedAt(), item.submittedAt(),item.executionPlannedAt(),item.executionStartedAt(),item.executionStartUnknown(),item.executionStartInferred(),item.executionPlanId(),item.outcome(),
                item.approvedAt(),item.rejectedAt(),item.executedAt(),item.cancelledAt(),item.expiredAt(),item.closedAt(),
                item.executionProgress().state(),item.executionProgress(),item.executionProgress().requiresAttention());
    }

    private static StepDto toStepDto(StepInstance si, RequestDetail detail) {
        List<Decision> decisions = detail.decisionsByStep().getOrDefault(si.getId(), List.of());
        return new StepDto(
                si.getId(),
                si.getPosition(),
                si.getStepCode(),
                si.getStepName(),
                si.getRequiredRole(),
                si.getState().name(),
                si.getRule().name(),
                si.getRequiredApprovals(),
                si.getSkipReason(),
                si.getBlockedReason(),
                si.getSatisfiedBy(),
                si.getSatisfiedAt(),
                decisions.stream().map(d -> new DecisionDto(
                        d.getId(),
                        d.getActorId(),
                        d.getActorUsername(),
                        d.getAction().name(),
                        d.getJustification(),
                        d.getEvidence(),
                        d.getDelegatedTo(),
                        d.getCreatedAt())).toList(), si.isRosterSnapshotted(), si.getRosterSize(), si.getBlockReasonCode(), si.getBlockedAt(), si.getBlockEpisode(),
                si.getRecoveryOwnerId(), si.getRecoveryTeam(), si.getRecoveredAt(), si.getActivatedAt(), si.getReviewDueAt(),
                si.getNextEscalationAt(), si.getExpiresAt(), si.getEscalationLevel(), si.getRecoveryOwnerRevision(), si.isRecoveryOwnerUnavailable(), si.getEscalationIntervalMinutes(), si.getMaxEscalationLevels());
    }

    public record RequesterDto(Long id, String username, String displayName) {
    }

    public record StepDto(
            Long id,
            int position,
            String code,
            String name,
            String requiredRole,
            String state,
            String rule,
            int requiredApprovals,
            String skipReason,
            String blockedReason,
            Long satisfiedBy,
            Instant satisfiedAt,
            List<DecisionDto> decisions,
            boolean rosterSnapshotted,
            Integer rosterSize,
            com.flowops.domain.request.BlockReason blockReasonCode,
            Instant blockedAt,
            int blockEpisode,
            Long recoveryOwnerId,
            String recoveryTeam,
            Instant recoveredAt,
            Instant activatedAt,
            Instant reviewDueAt,
            Instant nextEscalationAt,
            Instant expiresAt,
            int escalationLevel,
            int recoveryOwnerRevision,
            boolean recoveryOwnerUnavailable,
            int escalationIntervalMinutes,
            Integer maxEscalationLevels
    ) {
        public StepDto {
            // Stored reasons describe the last block episode; expose them as current only while blocked.
            if (!"BLOCKED".equals(state)) {
                blockedReason = null;
                blockReasonCode = null;
            }
        }
    }

    public record DecisionDto(
            Long id,
            Long actorId,
            String actorUsername,
            String action,
            String justification,
            Map<String, Object> evidence,
            Long delegatedTo,
            Instant createdAt
    ) {
    }

    public record ClarificationDto(
            Long id,
            Long askedBy,
            String question,
            String answer,
            Long answeredBy,
            Instant answeredAt,
            Instant createdAt
    ) {
    }

    public record ExecutionJobDto(
            Long id,
            String action,
            String status,
            Map<String, Object> result,
            String lastError,
            int retryCount,
            Instant createdAt,
            Instant completedAt,
            Instant startedAt,
            Instant cancelledAt,
            Long cancelledBy,
            int attemptCount,
            Instant leaseExpiresAt,
            boolean startInferred
    ) {
    }
}
