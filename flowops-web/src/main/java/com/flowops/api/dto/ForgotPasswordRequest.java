package com.flowops.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/forgot-password}.
 *
 * <p>
 * The endpoint always responds {@code 202 Accepted} — whether or not the address is known
 * — so the response never reveals account existence.
 */
public record ForgotPasswordRequest(@NotBlank(message = "email is required") @Email(
        message = "a valid email address is required") @Size(max = 255) String email) {
}
