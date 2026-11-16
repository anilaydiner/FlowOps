package com.flowops.domain.workflow;

import java.util.Map;

/**
 * A step specification supplied when creating or updating a workflow.
 *
 * <p>Kept separate from the API's {@code WorkflowRequest.StepDefinition} so the
 * application layer depends only on the domain. The controller maps the
 * request DTO onto this value object before calling the service.</p>
 */
public record StepSpec(
        Integer position,
        String code,
        String name,
        String requiredRole,
        CompletionRule completionRule,
        Integer requiredApprovals,
        Map<String, Object> condition,
        Integer slaMinutes,
        String escalationRole,
        Boolean terminal
) {}
