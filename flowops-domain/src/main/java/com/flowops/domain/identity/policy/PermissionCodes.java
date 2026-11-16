package com.flowops.domain.identity.policy;

/**
 * Registry of all permission codes understood by the authorization layer.
 *
 * <p>Permission codes use the {@code <resource>:<action>[:<scope>]} scheme.
 * Scoped variants (e.g. {@code request:approve:dept}) are matched by prefix
 * in {@link AuthorizationEvaluator}; only the base codes are declared here.
 *
 * <p><b>Modularization convention:</b> every controller endpoint maps to
 * exactly one permission code. Read and list are separate codes. Self-scoped
 * variants (e.g. {@code users:read:self}) are distinct codes enforced by the
 * service layer. The ADMIN role is not a hardcoded bypass — it simply holds
 * the codes it needs.
 *
 * <p>Seed data and the admin UI should reference these constants when
 * creating roles and permissions.
 */
public final class PermissionCodes {

    private PermissionCodes() {}

    // ── Users ──────────────────────────────────────────────────────────
    public static final String USERS_READ      = "users:read";
    public static final String USERS_READ_SELF = "users:read:self";
    public static final String USERS_LIST      = "users:list";
    public static final String USERS_CREATE    = "users:create";
    public static final String USERS_UPDATE    = "users:update";
    public static final String USERS_DELETE    = "users:delete";

    // ── Roles ──────────────────────────────────────────────────────────
    public static final String ROLES_READ      = "roles:read";
    public static final String ROLES_LIST      = "roles:list";
    public static final String ROLES_CREATE    = "roles:create";
    public static final String ROLES_UPDATE    = "roles:update";
    public static final String ROLES_DELETE    = "roles:delete";

    // ── Permissions ────────────────────────────────────────────────────
    public static final String PERMISSIONS_READ  = "permissions:read";
    public static final String PERMISSIONS_LIST  = "permissions:list";

    // ── Departments ────────────────────────────────────────────────────
    public static final String DEPARTMENTS_READ  = "departments:read";
    public static final String DEPARTMENTS_LIST  = "departments:list";

    // ── Request Types ──────────────────────────────────────────────────
    public static final String REQUEST_TYPES_READ    = "request-types:read";
    public static final String REQUEST_TYPES_LIST    = "request-types:list";
    public static final String REQUEST_TYPES_CREATE  = "request-types:create";
    public static final String REQUEST_TYPES_UPDATE  = "request-types:update";

    // ── Workflows ──────────────────────────────────────────────────────
    public static final String WORKFLOWS_READ    = "workflows:read";
    public static final String WORKFLOWS_LIST    = "workflows:list";
    public static final String WORKFLOWS_CREATE  = "workflows:create";
    public static final String WORKFLOWS_UPDATE  = "workflows:update";

    // ── Requests ───────────────────────────────────────────────────────
    public static final String REQUEST_CREATE       = "request:create";
    public static final String REQUEST_READ         = "request:read";
    public static final String REQUEST_READ_OWN     = "request:read:own";
    public static final String REQUEST_LIST         = "request:list";
    public static final String REQUEST_LIST_OWN     = "request:list:own";
    public static final String REQUEST_UPDATE       = "request:update";
    public static final String REQUEST_UPDATE_OWN   = "request:update:own";
    public static final String REQUEST_CANCEL       = "request:cancel";
    public static final String REQUEST_CANCEL_OWN   = "request:cancel:own";
    public static final String REQUEST_SUBMIT       = "request:submit";
    public static final String REQUEST_SUBMIT_OWN   = "request:submit:own";
    public static final String REQUEST_QUEUE        = "request:queue";
    public static final String REQUEST_APPROVE      = "request:approve";
    public static final String REQUEST_REJECT       = "request:reject";
    public static final String REQUEST_CLOSE        = "request:close";
    public static final String REQUEST_CLOSE_OWN    = "request:close:own";
    public static final String REQUEST_OVERRIDE_SOD = "request:override:sod";

    // ── Clarifications ─────────────────────────────────────────────────
    public static final String CLARIFICATIONS_ASK        = "clarifications:ask";
    public static final String CLARIFICATIONS_ASK_OWN    = "clarifications:ask:own";
    public static final String CLARIFICATIONS_ANSWER     = "clarifications:answer";
    public static final String CLARIFICATIONS_ANSWER_OWN = "clarifications:answer:own";
    public static final String CLARIFICATIONS_READ       = "clarifications:read";
    public static final String CLARIFICATIONS_READ_OWN   = "clarifications:read:own";

    // ── Audit ──────────────────────────────────────────────────────────
    public static final String AUDIT_READ     = "audit:read";
    public static final String AUDIT_READ_ANY = "audit:read:any";

    // ── Notifications ──────────────────────────────────────────────────
    public static final String NOTIFICATIONS_READ   = "notifications:read";
    public static final String NOTIFICATIONS_UPDATE = "notifications:update";
}
