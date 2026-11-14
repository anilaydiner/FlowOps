package com.flowops.api.dto;

import com.flowops.domain.Clarification;
import com.flowops.domain.Decision;
import com.flowops.domain.ExecutionJob;
import com.flowops.domain.Request;
import com.flowops.domain.StepInstance;

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
        Instant submittedAt
) {
    public static RequestResponse from(Request r) {
        com.flowops.domain.Department dept = r.getType().getOwningDepartment();
        List<StepDto> stepDtos = (r.getLoadedSteps() == null) ? List.of()
                : r.getLoadedSteps().stream().map(si -> toStepDto(si, r)).toList();

        List<ClarificationDto> clariDtos = (r.getLoadedClarifications() == null) ? List.of()
                : r.getLoadedClarifications().stream().map(c -> new ClarificationDto(
                        c.getId(), c.getAskedBy(), c.getQuestion(),
                        c.getAnswer(), c.getAnsweredBy(), c.getAnsweredAt(), c.getCreatedAt()
                )).toList();

        List<ExecutionJobDto> jobDtos = (r.getLoadedJobs() == null) ? List.of()
                : r.getLoadedJobs().stream().map(j -> new ExecutionJobDto(
                        j.getId(), j.getAction(), j.getStatus().name(),
                        j.getResult(), j.getLastError(), j.getRetryCount(),
                        j.getCreatedAt(), j.getCompletedAt()
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
                r.getSubmittedAt());
    }

    private static StepDto toStepDto(StepInstance si, Request r) {
        List<Decision> decisions = (r.getLoadedDecisions() == null)
                ? List.of()
                : r.getLoadedDecisions().getOrDefault(si.getId(), List.of());
        return new StepDto(
                si.getId(),
                si.getPosition(),
                si.getStep().getCode(),
                si.getStep().getName(),
                si.getStep().getRequiredRole(),
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
                        d.getCreatedAt())).toList());
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
            List<DecisionDto> decisions
    ) {
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
            Instant completedAt
    ) {
    }
}
