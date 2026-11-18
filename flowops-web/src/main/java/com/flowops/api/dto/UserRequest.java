package com.flowops.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UserRequest(

        @NotBlank(message = "username is required")
        @Pattern(regexp = "^[a-z0-9._-]{3,64}$",
                message = "username must be 3-64 chars: lowercase letters, digits, dot, dash, underscore")
        String username,

        @Size(min = 1, max = 100, message = "displayName must be 1-100 characters")
        String displayName,

        @Email(message = "email must be a valid address")
        @Size(max = 255)
        String email,

        @Size(max = 32)
        String departmentCode,

        /** Role codes to assign (e.g. ["ADMIN", "MEMBER"]). Null/empty defaults to MEMBER. */
        List<String> roles,

        @Size(min = 8, max = 100, message = "password must be 8-100 characters")
        String password,
        Boolean active
) {
    public UserRequest(String username,String displayName,String email,String departmentCode,List<String> roles,String password) {
        this(username,displayName,email,departmentCode,roles,password,null);
    }
}
