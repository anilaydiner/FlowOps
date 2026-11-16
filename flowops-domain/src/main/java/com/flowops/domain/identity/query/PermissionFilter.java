package com.flowops.domain.identity.query;

public record PermissionFilter(String code, String name) {
    public boolean isEmpty() {
        return code == null && name == null;
    }
}
