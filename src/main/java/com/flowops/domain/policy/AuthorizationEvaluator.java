package com.flowops.domain.policy;

import com.flowops.domain.Permission;
import com.flowops.domain.Role;
import com.flowops.domain.User;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scoped authorization evaluator (PLAN §7).
 *
 * <p>Checks whether a user is allowed to perform an action on a target,
 * considering:
 * <ol>
 *   <li><b>Permission</b> — the user must hold the permission code
 *       (via any of their roles).</li>
 *   <li><b>Scope</b> — if the permission carries a scope constraint,
 *       the target must satisfy it.</li>
 *   <li><b>SoD</b> — separation of duties: the requester cannot act on
 *       their own request (unless they hold {@code request:override:sod}).</li>
 * </ol>
 *
 * <p>Every decision carries an <b>evidence</b> array explaining which
 * permission, role, and scope matched (or why it didn't) — stored in
 * {@code decisions.evidence} and visible in the API response.
 */
public class AuthorizationEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationEvaluator.class);

    public static final String PERM_APPROVE = PermissionCodes.REQUEST_APPROVE;
    public static final String PERM_REJECT  = PermissionCodes.REQUEST_REJECT;
    public static final String PERM_CLOSE   = PermissionCodes.REQUEST_CLOSE;
    public static final String PERM_OVERRIDE_SOD = PermissionCodes.REQUEST_OVERRIDE_SOD;

    private final UserRepository users;

    public AuthorizationEvaluator(UserRepository users) {
        this.users = users;
    }

    /**
     * Authorize an action on a target.
     *
     * @param caller          the authenticated user
     * @param permissionCode  the required permission (e.g. "request:approve")
     * @param target          the target context (request + step attributes)
     * @return decision with evidence trail
     */
    public AuthorizationDecision authorize(CallerIdentity caller,
                                           String permissionCode,
                                           TargetContext target) {
        return authorize(caller, permissionCode, target, false);
    }

    /**
     * Authorize an action on a target, with optional SoD bypass.
     *
     * @param skipSod when true, the separation-of-duties check is skipped
     *                (used for close: the requester closing their own request is legitimate)
     */
    public AuthorizationDecision authorize(CallerIdentity caller,
                                           String permissionCode,
                                           TargetContext target,
                                           boolean skipSod) {
        List<String> evidence = new ArrayList<>();
        User user = users.findById(caller.id())
                .orElseThrow(() -> new IllegalStateException("Caller not found: " + caller.id()));

        // 1 ── SoD check (default deny; overridable by request:override:sod)
        if (!skipSod && target != null && target.requesterId() != null
                && target.requesterId().equals(caller.id())) {
            if (!hasPermission(user, PERM_OVERRIDE_SOD)) {
                evidence.add("SoD: requester cannot act on own request");
                evidence.add("override: not granted");
                log.debug("SoD deny for {} on request {}", caller.username(), target);
                return AuthorizationDecision.deny(evidence);
            }
            evidence.add("SoD: overridden by request:override:sod");
        }

        // 2 ── Find a matching permission (any scope variant)
        Permission match = findMatchingPermission(user, permissionCode);
        if (match == null) {
            evidence.add("permission=" + permissionCode + " not granted to any role");
            return AuthorizationDecision.deny(evidence);
        }

        evidence.add("permission=" + match.getCode());
        evidence.add("scope=" + match.scopeLabel());
        evidence.add("role=" + grantingRole(user, match));

        // 3 ── Scope check
        if (match.getScopeType() != null && target != null) {
            boolean scopeOk = checkScope(user, match, target, evidence);
            if (!scopeOk) {
                return AuthorizationDecision.deny(evidence);
            }
        }

        evidence.add("result=ALLOWED");
        return AuthorizationDecision.allow(evidence);
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
     * Find the best-matching permission for the given code.
     * Prefers the most specific scope (global > scoped) — actually,
     * we want any permission that covers the action. If the user has
     * both a global and a scoped permission, the global one wins
     * (no scope constraint to fail).
     */
    private Permission findMatchingPermission(User user, String permissionCode) {
        // Collect all permissions whose code equals the base code OR is a scoped variant
        // (e.g. base "request:approve" matches "request:approve:dept", "request:approve:threshold")
        List<Permission> all = new ArrayList<>();
        for (Role role : user.getRoles()) {
            if (role.getPermissions() == null) continue;
            for (Permission p : role.getPermissions()) {
                String code = p.getCode();
                if (code.equals(permissionCode) || code.startsWith(permissionCode + ":")) {
                    all.add(p);
                }
            }
        }
        if (all.isEmpty()) return null;

        // Prefer global (no scope) — it's the most permissive
        return all.stream()
                .filter(Permission::isGlobal)
                .findFirst()
                .orElse(all.get(0));
    }

    private boolean checkScope(User user, Permission perm, TargetContext target,
                               List<String> evidence) {
        return switch (perm.getScopeType()) {
            case SELF -> {
                boolean ok = target.requesterId() != null
                        && target.requesterId().equals(user.getId());
                evidence.add("scope_check=SELF " + (ok ? "match" : "mismatch (target.requester="
                        + target.requesterId() + ", user=" + user.getId() + ")"));
                yield ok;
            }
            case DEPARTMENT -> {
                com.flowops.domain.Department userDept = user.getDepartment();
                if (userDept == null) {
                    evidence.add("scope_check=DEPARTMENT deny (user has no department)");
                    yield false;
                }
                boolean ok = userDept.getCode().equalsIgnoreCase(target.departmentCode());
                evidence.add("scope_check=DEPARTMENT user=" + userDept.getCode()
                        + " target=" + target.departmentCode()
                        + " " + (ok ? "match" : "mismatch"));
                yield ok;
            }
            case REQUEST_TYPE -> {
                boolean ok = perm.getScopeValue() != null
                        && perm.getScopeValue().equalsIgnoreCase(target.typeCode());
                evidence.add("scope_check=REQUEST_TYPE scope=" + perm.getScopeValue()
                        + " target=" + target.typeCode()
                        + " " + (ok ? "match" : "mismatch"));
                yield ok;
            }
            case STEP -> {
                boolean ok = perm.getScopeValue() != null
                        && perm.getScopeValue().equalsIgnoreCase(target.stepCode());
                evidence.add("scope_check=STEP scope=" + perm.getScopeValue()
                        + " target=" + target.stepCode()
                        + " " + (ok ? "match" : "mismatch"));
                yield ok;
            }
            case THRESHOLD -> {
                double max = parseDouble(perm.getScopeValue(), 0);
                double amount = target.amount() != null ? target.amount() : 0;
                boolean ok = amount <= max;
                evidence.add("scope_check=THRESHOLD max=" + max
                        + " amount=" + amount
                        + " " + (ok ? "within" : "exceeded"));
                yield ok;
            }
        };
    }

    private boolean hasPermission(User user, String code) {
        for (Role role : user.getRoles()) {
            if (role.getPermissions() == null) continue;
            for (Permission p : role.getPermissions()) {
                if (p.getCode().equals(code)) return true;
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

    private static double parseDouble(String s, double fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
    }
}
