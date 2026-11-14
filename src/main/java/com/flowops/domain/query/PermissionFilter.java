package com.flowops.domain.query;

public record PermissionFilter(String code, String name) {
    public boolean isEmpty() {
        return code == null && name == null;
    }
}
