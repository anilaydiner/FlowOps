package com.flowops.api.dto;

import com.flowops.domain.Department;
import com.flowops.domain.User;

import java.time.Instant;
import java.util.List;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        String email,
        String departmentCode,
        String departmentName,
        List<String> roles,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserResponse from(User user) {
        Department dept = user.getDepartment();
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                dept != null ? dept.getCode() : null,
                dept != null ? dept.getName() : null,
                user.roleCodes().stream().toList(),
                user.isActive(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
