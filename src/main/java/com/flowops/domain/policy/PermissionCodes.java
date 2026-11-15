package com.flowops.domain.policy;

/**
 * Registry of all permission codes understood by the authorization layer.
 *
 * <p>Permission codes use the {@code <resource>:<action>[:<scope>]} scheme.
 * Scoped variants (e.g. {@code request:approve:dept}) are matched by prefix
 * in {@link AuthorizationEvaluator}; only the base codes are declared here.
 *
 * <p>Seed data and the admin UI should reference these constants when
 * creating roles and permissions.
 */
public final class PermissionCodes {

    private PermissionCodes() {}

    public static final String REQUEST_APPROVE   = "request:approve";
    public static final String REQUEST_REJECT    = "request:reject";
    public static final String REQUEST_CLOSE     = "request:close";
    public static final String REQUEST_CLOSE_OWN = "request:close:own";
    public static final String REQUEST_OVERRIDE_SOD = "request:override:sod";

    public static final String AUDIT_READ_ANY    = "audit:read:any";
}
