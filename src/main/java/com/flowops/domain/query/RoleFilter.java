package com.flowops.domain.query;

public record RoleFilter(String code, String name) {
    public boolean isEmpty() {
        return code == null && name == null;
    }
}
