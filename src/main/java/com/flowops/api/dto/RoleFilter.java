package com.flowops.api.dto;

public record RoleFilter(String code, String name) {
    public boolean isEmpty() {
        return code == null && name == null;
    }
}
