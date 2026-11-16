package com.flowops.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(
        @NotBlank(message = "username is required")
        @Size(max = 64)
        String username,

        @NotBlank(message = "password is required")
        @Size(max = 100)
        String password
) {
}
