package com.flowops.domain.query;

public record RequestTypeFilter(
        String code,
        String name,
        String departmentCode,
        Boolean active
) {
    public boolean isEmpty() {
        return code == null
                && name == null
                && departmentCode == null
                && active == null;
    }
}
