package com.flowops.api.dto;

import com.flowops.domain.identity.User;
import com.flowops.domain.organization.Department;

/**
 * A user the caller may delegate a decision to.
 *
 * <p>Only the fields the front-end needs to render a delegatee picker are
 * exposed — no email, password hash, or other sensitive data.
 */
public record EligibleDelegateeResponse(
        Long id,
        String username,
        String displayName,
        String departmentCode
) {
    public static EligibleDelegateeResponse from(User user) {
        Department dept = user.getDepartment();
        return new EligibleDelegateeResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                dept != null ? dept.getCode() : null);
    }
}
