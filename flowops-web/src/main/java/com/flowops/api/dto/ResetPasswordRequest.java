package com.flowops.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/reset-password}.
 *
 * <p>
 * {@code token} is the value from the {@code ?token=...} query parameter of the reset
 * link delivered by email.
 */
public record ResetPasswordRequest(@NotBlank(message = "token is required") @Size(max = 255) String token,

        @NotBlank(message = "newPassword is required") @Size(min = 8, max = 100,
                message = "password must be 8-100 characters") String newPassword) {
}
