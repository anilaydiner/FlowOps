package com.flowops.api.dto;

public record WorkflowFilter(
        String code,
        String name
) {
    public boolean isEmpty() {
        return (code == null || code.isBlank()) && (name == null || name.isBlank());
    }
}
