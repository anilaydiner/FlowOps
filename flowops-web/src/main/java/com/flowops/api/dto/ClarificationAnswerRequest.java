package com.flowops.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClarificationAnswerRequest(@NotBlank @Size(max = 4000) String answer) {
}
