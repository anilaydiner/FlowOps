package com.flowops.api.dto;

/**
 * Optional filter criteria for the user list endpoint.
 * All fields are optional; only non-null values participate in the query.
 *
 * @param username       partial match (ILIKE)
 * @param displayName    partial match (ILIKE)
 * @param email          partial match (ILIKE)
 * @param departmentCode exact match on department.code
 * @param roleCode       exact match on role.code (e.g. ADMIN)
 * @param active         exact match on user.active
 */
public record UserFilter(
        String username,
        String displayName,
        String email,
        String departmentCode,
        String roleCode,
        Boolean active
) {
    /** True when no filter criteria were supplied. */
    public boolean isEmpty() {
        return username == null
                && displayName == null
                && email == null
                && departmentCode == null
                && roleCode == null
                && active == null;
    }
}
