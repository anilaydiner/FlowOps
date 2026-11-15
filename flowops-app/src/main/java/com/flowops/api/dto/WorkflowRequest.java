package com.flowops.api.dto;

import com.flowops.domain.CompletionRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record WorkflowRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull @Valid @Size(min = 1, message = "at least one step is required")
        List<StepDefinition> steps,
        Boolean active
) {
    public record StepDefinition(
            Integer position,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 64) String requiredRole,
            CompletionRule completionRule,
            Integer requiredApprovals,
            Map<String, Object> condition,
            Integer slaMinutes,
            @Size(max = 64) String escalationRole,
            Boolean terminal
    ) {
    }
}
