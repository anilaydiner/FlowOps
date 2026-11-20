package com.flowops.domain.identity.policy;

import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.Permission;
import com.flowops.domain.identity.Role;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.port.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scoped authorization evaluator (PLAN §7).
 *
 * <p>
 * Checks whether a user is allowed to perform an action on a target, considering:
 * <ol>
 * <li><b>Permission</b> — the user must hold the permission code (via any of their
 * roles).</li>
 * <li><b>Scope</b> — if the permission carries a scope constraint, the target must
 * satisfy it.</li>
 * <li><b>SoD</b> — separation of duties: the requester cannot act on their own request
 * (unless they hold {@code request:override:sod}).</li>
 * </ol>
 *
 * <p>
 * Every decision carries an <b>evidence</b> array explaining which permission, role, and
 * scope matched (or why it didn't) — stored in {@code decisions.evidence} and visible in
 * the API response.
 */
public class AuthorizationEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationEvaluator.class);

    public static final String PERM_APPROVE = PermissionCodes.REQUEST_APPROVE;

    public static final String PERM_REJECT = PermissionCodes.REQUEST_REJECT;

    public static final String PERM_CLOSE = PermissionCodes.REQUEST_CLOSE;

    public static final String PERM_OVERRIDE_SOD = PermissionCodes.REQUEST_OVERRIDE_SOD;

    private final UserRepository users;

    public AuthorizationEvaluator(UserRepository users) {
        this.users = users;
    }

    /**
     * Authorize an action on a target.
     * @param caller the authenticated user
     * @param permissionCode the required permission (e.g. "request:approve")
     * @param target the target context (request + step attributes)
     * @return decision with evidence trail
     */
    public AuthorizationDecision authorize(CallerIdentity caller, String permissionCode, TargetContext target) {
        return authorize(caller, permissionCode, target, false);
    }

    /**
     * Authorize or throw. Convenience wrapper around {@link #authorize} that throws
     * {@link com.flowops.domain.exception.ForbiddenException} with the evidence trail
     * when the decision is denied.
     * @param caller the authenticated user
     * @param permissionCode the required permission code
     * @throws com.flowops.domain.exception.ForbiddenException if denied
     */
    public void require(CallerIdentity caller, String permissionCode) {
        require(caller, permissionCode, null, false);
    }

    /** Authorize with a target context (scope + SoD checks apply). */
    public void require(CallerIdentity caller, String permissionCode, TargetContext target) {
        require(caller, permissionCode, target, false);
    }

    /** Authorize with target + optional SoD bypass. */
    public void require(CallerIdentity caller, String permissionCode, TargetContext target, boolean skipSod) {
        AuthorizationDecision d = authorize(caller, permissionCode, target, skipSod);
        if (!d.allowed()) {
            throw new com.flowops.domain.exception.ForbiddenException("Missing permission: " + permissionCode,
                    d.evidence());
        }
    }

    /**
     * Authorize an action on a target, with optional SoD bypass.
     * @param skipSod when true, the separation-of-duties check is skipped (used for
     * close: the requester closing their own request is legitimate)
     */
    public AuthorizationDecision authorize(CallerIdentity caller, String permissionCode, TargetContext target,
            boolean skipSod) {
        User user = users.findById(caller.id())
            .orElseThrow(() -> new IllegalStateException("Caller not found: " + caller.id()));
        return evaluate(user, permissionCode, target, skipSod);
    }

    /**
     * Evaluate whether a specific user may perform an action on a target.
     *
     * <p>
     * Same semantics as {@link #authorize} but operates on a {@link User} aggregate
     * directly (no re-fetch) — used for <b>eligibility probes</b> such as listing who a
     * decision may be delegated to: the delegatee must pass exactly the gate they will
     * face when acting.
     */
    public AuthorizationDecision evaluate(User user, String permissionCode, TargetContext target) {
        return evaluate(user, permissionCode, target, false);
    }

    /** Core evaluation with optional SoD bypass. */
    public AuthorizationDecision evaluate(User user, String permissionCode, TargetContext target, boolean skipSod) {
        if (user == null) {
            throw new IllegalArgumentException("user is required");
        }
        List<String> evidence = new ArrayList<>();

        // 1 ── SoD check (default deny; overridable by request:override:sod)
        if (!skipSod && target != null && target.requesterId() != null && target.requesterId().equals(user.getId())) {
            if (!hasPermission(user, PERM_OVERRIDE_SOD)) {
                evidence.add("SoD: requester cannot act on own request");
                evidence.add("override: not granted");
                log.debug("SoD deny for {} on request {}", user.getUsername(), target);
                return AuthorizationDecision.deny(evidence);
            }
            evidence.add("SoD: overridden by request:override:sod");
        }

        // 2 ── Find all matching permissions (scoped variants only when target present)
        List<Permission> candidates = findMatchingPermissions(user, permissionCode, target);
        if (candidates.isEmpty()) {
            evidence.add("permission=" + permissionCode + " not granted to any role");
            return AuthorizationDecision.deny(evidence);
        }

        // 3 ── Try each candidate; the first one that passes the scope check wins
        Permission winner = null;
        for (Permission p : candidates) {
            List<String> subEvidence = new ArrayList<>();
            boolean scopeOk = (p.getScopeType() == null || target == null) || checkScope(user, p, target, subEvidence);
            if (scopeOk) {
                winner = p;
                evidence.addAll(subEvidence);
                break;
            }
            // Log the failed attempt for the evidence trail
            evidence.addAll(subEvidence);
            evidence.add("candidate_denied: permission=" + p.getCode() + " scope=" + p.scopeLabel());
        }

        if (winner == null) {
            evidence.add("result=DENIED (no candidate passed scope check)");
            return AuthorizationDecision.deny(evidence);
        }

        evidence.add("permission=" + winner.getCode());
        evidence.add("scope=" + winner.scopeLabel());
        evidence.add("role=" + grantingRole(user, winner));
        evidence.add("result=ALLOWED");
        return AuthorizationDecision.allow(evidence);
    }

    /**
     * Check if the caller holds the given permission code (no scope or SoD checks).
     * Useful for determining access level — e.g. "can list all" vs "own only".
     */
    public boolean hasPermission(CallerIdentity caller, String permissionCode) {
        User user = users.findById(caller.id())
            .orElseThrow(() -> new IllegalStateException("Caller not found: " + caller.id()));
        return hasPermission(user, permissionCode);
    }

    /**
     * Check only the SoD rule (used by the engine before the full authorize).
     */
    public boolean sodAllows(CallerIdentity caller, Long requesterId) {
        if (caller.id().equals(requesterId)) {
            return hasPermission(caller.id(), PERM_OVERRIDE_SOD);
        }
        return true;
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Find all matching permissions for the given code.
     *
     * <p>
     * When a {@code target} is provided, scoped variants (e.g.
     * {@code request:approve:dept}) are considered alongside the base code. When
     * {@code target} is null (non-request resources such as users, roles), only an
     * <b>exact</b> code match is accepted — this prevents {@code users:read:self} from
     * satisfying a check for {@code users:read}.
     *
     * <p>
     * Global (no-scope) permissions are sorted first — they're the most permissive and
     * most likely to pass without a scope check.
     */
    private List<Permission> findMatchingPermissions(User user, String permissionCode, TargetContext target) {
        List<Permission> all = new ArrayList<>();
        for (Role role : user.getRoles()) {
            if (role.getPermissions() == null)
                continue;
            for (Permission p : role.getPermissions()) {
                String code = p.getCode();
                if (code.equals(permissionCode)) {
                    all.add(p);
                }
                else if (target != null && code.startsWith(permissionCode + ":")) {
                    all.add(p);
                }
            }
        }
        // Global (no scope) first, then scoped variants
        all.sort(Comparator.comparing(Permission::isGlobal, Comparator.reverseOrder()));
        return all;
    }

    private boolean checkScope(User user, Permission perm, TargetContext target, List<String> evidence) {
        boolean allowed = new PermissionScope(perm.getScopeType(), perm.getScopeValue()).matches(user.getId(),
                user.getDepartment() == null ? null : user.getDepartment().getCode(), target);
        evidence.add("scope_check=" + perm.getScopeType() + " target.type=" + target.typeCode() + " target.department="
                + target.departmentCode() + " " + (allowed ? "match" : "mismatch"));
        return allowed;
    }

    private boolean hasPermission(User user, String code) {
        for (Role role : user.getRoles()) {
            if (role.getPermissions() == null)
                continue;
            for (Permission p : role.getPermissions()) {
                if (p.getCode().equals(code))
                    return true;
            }
        }
        return false;
    }

    private boolean hasPermission(Long userId, String code) {
        User user = users.findById(userId).orElse(null);
        return user != null && hasPermission(user, code);
    }

    private String grantingRole(User user, Permission perm) {
        for (Role role : user.getRoles()) {
            if (role.getPermissions() != null && role.getPermissions().contains(perm)) {
                return role.getCode();
            }
        }
        return "unknown";
    }

}
