package com.flowops.domain.identity.query;

public record RoleFilter(String code, String name) {
    public boolean isEmpty() {
        return code == null && name == null;
    }
}
