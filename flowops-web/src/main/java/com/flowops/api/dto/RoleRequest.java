package com.flowops.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RoleRequest(

        @NotBlank(message = "code is required")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,31}$",
                message = "code must be 3-32 uppercase chars: letters, digits, underscore (e.g. ADMIN)")
        String code,

        @NotBlank(message = "name is required")
        @Size(min = 1, max = 100)
        String name,

        @Size(max = 500)
        String description,

        /** Permission codes to assign (e.g. ["users:read", "roles:write"]). */
        List<String> permissions
) {
}
