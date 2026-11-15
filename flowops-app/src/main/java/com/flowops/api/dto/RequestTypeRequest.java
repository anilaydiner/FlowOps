package com.flowops.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record RequestTypeRequest(

        @NotBlank(message = "code is required")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,31}$",
                message = "code must be 3-32 uppercase chars: letters, digits, underscore (e.g. EXPENSE)")
        String code,

        @NotBlank(message = "name is required")
        @Size(min = 1, max = 100)
        String name,

        @Size(max = 500)
        String description,

        /** Owning department code (e.g. FINANCE). */
        @Size(max = 32)
        String departmentCode,

        /** Default workflow code (e.g. WF_EXPENSE) used when the request is submitted. */
        @Size(max = 64)
        String workflowCode,

        /** Required payload fields: field name → description. */
        Map<String, String> requiredFields,

        /** Validation rules: field name → rule (e.g. {"type":"number","min":1}). */
        Map<String, Object> validationRules
) {
}
