package com.flowops.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record UpdateRequestRequest(

        @NotNull(message = "payload is required (use {} for no fields)") Map<String, Object> payload) {
}
