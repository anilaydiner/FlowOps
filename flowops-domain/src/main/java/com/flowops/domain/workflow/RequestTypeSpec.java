package com.flowops.domain.workflow;

import java.util.Map;

/**
 * Input specification for creating or updating a request type.
 *
 * <p>Kept separate from the API's {@code RequestTypeRequest} so the application
 * layer depends only on the domain. The controller maps the request DTO onto
 * this value object before calling the service.</p>
 */
public record RequestTypeSpec(
        String code,
        String name,
        String description,
        String departmentCode,
        String workflowCode,
        Map<String, String> requiredFields,
        Map<String, Object> validationRules
) {}
