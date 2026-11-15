package com.flowops.api.dto;

import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowStep;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowResponse(
        Long id,
        String code,
        String name,
        String description,
        boolean active,
        List<StepResponse> steps,
        Instant createdAt,
        Instant updatedAt
) {
    public static WorkflowResponse from(Workflow workflow, List<WorkflowStep> stepList) {
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getCode(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.isActive(),
                stepList.stream().map(StepResponse::from).toList(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt());
    }

    public record StepResponse(
            Long id,
            int position,
            String code,
            String name,
            String requiredRole,
            String rule,
            int requiredApprovals,
            Map<String, Object> condition,
            Integer slaMinutes,
            String escalationRole,
            boolean terminal
    ) {
        public static StepResponse from(WorkflowStep s) {
            return new StepResponse(
                    s.getId(),
                    s.getPosition(),
                    s.getCode(),
                    s.getName(),
                    s.getRequiredRole(),
                    s.getCompletionRule().name(),
                    s.getRequiredApprovals(),
                    s.getCondition(),
                    s.getSlaMinutes(),
                    s.getEscalationRole(),
                    s.isTerminal());
        }
    }
}
