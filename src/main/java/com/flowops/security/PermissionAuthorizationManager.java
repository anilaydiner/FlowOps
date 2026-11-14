package com.flowops.security;

import com.flowops.domain.UserRepository;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * Spring Security {@link AuthorizationManager} that checks whether the
 * authenticated user holds a specific admin permission (coarse controller guard).
 *
 * <p>Implementation: checks the {@link UserPrincipal#roles()} set (from JWT)
 * for the ADMIN role. The ADMIN role is granted all {@code admin:*} permissions
 * in the permission catalog (V6 migration), so role membership implies permission.
 *
 * <p>This avoids a DB round-trip in the security filter chain — the roles are
 * already in the JWT claims. The fine-grained scope checks (DEPARTMENT,
 * THRESHOLD, SoD) are handled by {@code AuthorizationEvaluator} in the
 * workflow engine.
 *
 * <p>Usage in {@code SecurityConfig}:
 * <pre>{@code
 * .requestMatchers(POST, "/api/v1/users")
 *    .access(new PermissionAuthorizationManager(users))
 * }</pre>
 */
public class PermissionAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private final UserRepository users;

    public PermissionAuthorizationManager(UserRepository users) {
        this.users = users;
    }

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
                                           RequestAuthorizationContext object) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        Object principal = auth.getPrincipal();
        if (!(principal instanceof UserPrincipal up)) {
            return new AuthorizationDecision(false);
        }

        // Coarse check: ADMIN role implies all admin:* permissions.
        // Fine-grained checks (scope, SoD, eligibility) are in AuthorizationEvaluator.
        boolean granted = up.isAdmin();
        return new AuthorizationDecision(granted);
    }
}
