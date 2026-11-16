package com.flowops.domain.organization.query;

/**
 * Optional filter criteria for the department list endpoint.
 */
public record DepartmentFilter(
        String code,
        String name
) {
    public boolean isEmpty() {
        return (code == null || code.isBlank())
                && (name == null || name.isBlank());
    }
}
