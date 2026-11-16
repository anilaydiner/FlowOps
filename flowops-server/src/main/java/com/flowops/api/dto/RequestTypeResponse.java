package com.flowops.api.dto;

import com.flowops.domain.organization.Department;
import com.flowops.domain.workflow.RequestType;
import java.time.Instant;
import java.util.Map;

public record RequestTypeResponse(
        Long id,
        String code,
        String name,
        String description,
        String departmentCode,
        String departmentName,
        Long defaultWorkflowId,
        Map<String, String> requiredFields,
        Map<String, Object> validationRules,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    @SuppressWarnings("unchecked")
    public static RequestTypeResponse from(RequestType rt) {
        Department dept = rt.getOwningDepartment();
        return new RequestTypeResponse(
                rt.getId(),
                rt.getCode(),
                rt.getName(),
                rt.getDescription(),
                dept != null ? dept.getCode() : null,
                dept != null ? dept.getName() : null,
                rt.getDefaultWorkflowId(),
                (Map<String, String>) (Map<?, ?>) rt.getRequiredFields(),
                rt.getValidationRules(),
                rt.isActive(),
                rt.getCreatedAt(),
                rt.getUpdatedAt());
    }
}
