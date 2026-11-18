package com.flowops.api.dto;

import com.flowops.domain.identity.Permission;
import java.time.Instant;

public record PermissionResponse(
        Long id,
        String code,
        String name,
        String description,
        String scopeType,
        String scopeValue,
        Instant createdAt
) {
    public static PermissionResponse from(Permission p) {
        return new PermissionResponse(
                p.getId(),
                p.getCode(),
                p.getName(),
                p.getDescription(),
                p.getScopeType() != null ? p.getScopeType().name() : null,
                p.getScopeValue(),
                p.getCreatedAt()
        );
    }
}
