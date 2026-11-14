package com.flowops.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateRequestRequest(

        @NotBlank(message = "typeCode is required")
        @Size(max = 32)
        String typeCode,

        @NotNull(message = "payload is required (use {} for no fields)")
        Map<String, Object> payload
) {
}
