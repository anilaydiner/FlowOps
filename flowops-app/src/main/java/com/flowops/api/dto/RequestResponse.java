package com.flowops.api.dto;

import com.flowops.domain.Clarification;
import com.flowops.domain.Decision;
import com.flowops.domain.ExecutionJob;
import com.flowops.domain.Request;
import com.flowops.application.model.RequestDetail;
import com.flowops.application.model.RequestListItem;
import com.flowops.application.model.RequestDetailView;
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
    public static RequestResponse from(RequestDetail detail) {
        Request r = detail.request();
        com.flowops.domain.Department dept = r.getType().getOwningDepartment();
        List<StepDto> stepDtos = detail.steps().stream().map(si -> toStepDto(si, detail)).toList();

        List<ClarificationDto> clariDtos = detail.clarifications().stream().map(c -> new ClarificationDto(
                c.getId(), c.getAskedBy(), c.getQuestion(),
                c.getAnswer(), c.getAnsweredBy(), c.getAnsweredAt(), c.getCreatedAt()
        )).toList();

        List<ExecutionJobDto> jobDtos = detail.jobs().stream().map(j -> new ExecutionJobDto(
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

    public static RequestResponse from(RequestListItem item) {
        return new RequestResponse(item.id(), item.typeCode(), item.typeName(), item.departmentCode(),
                item.status().name(), item.payload(), item.version() != null ? item.version() : 0L,
                new RequesterDto(item.requesterId(), item.requesterUsername(), item.requesterDisplayName()),
                item.correlationId(), List.of(), List.of(), List.of(),
                item.createdAt(), item.updatedAt(), item.submittedAt());
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
                                        decision.delegatedTo(), decision.createdAt())).toList())).toList(),
                detail.clarifications().stream().map(clarification -> new ClarificationDto(
                        clarification.id(), clarification.askedBy(), clarification.question(), clarification.answer(),
                        clarification.answeredBy(), clarification.answeredAt(), clarification.createdAt())).toList(),
                detail.jobs().stream().map(job -> new ExecutionJobDto(job.id(), job.action(), job.status().name(),
                        job.result(), job.lastError(), job.retryCount(), job.createdAt(), job.completedAt())).toList(),
                item.createdAt(), item.updatedAt(), item.submittedAt());
    }

    private static StepDto toStepDto(StepInstance si, RequestDetail detail) {
        List<Decision> decisions = detail.decisionsByStep().getOrDefault(si.getId(), List.of());
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
