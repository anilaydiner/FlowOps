package com.flowops.api.dto;

import com.flowops.domain.identity.Role;
import java.time.Instant;
import java.util.List;

public record RoleResponse(
        Long id,
        String code,
        String name,
        String description,
        List<String> permissions,
        Instant createdAt
) {
    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.permissionCodes().stream().toList(),
                role.getCreatedAt());
    }
}
