package com.flowops.api.dto;

public record PermissionFilter(String code, String name) {
    public boolean isEmpty() {
        return code == null && name == null;
    }
}
