package com.flowops.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClarificationRequest(@NotBlank @Size(max = 2000) String question) {
}
