package com.flowops.domain.identity;

/**
 * Scope constraint types for permissions (PLAN §7). A null scope_type on a permission
 * means "global" — no scope constraint.
 */
public enum ScopeType {

    /** Only on the user's own resources. */
    SELF,
    /**
     * Only on resources in the user's department (departmentless users: default deny).
     */
    DEPARTMENT,
    /** Only on a specific request type (scope_value = type code). */
    REQUEST_TYPE,
    /** Only on a specific workflow step (scope_value = step code). */
    STEP,
    /** Only on resources up to a threshold amount (scope_value = max amount). */
    THRESHOLD

}
