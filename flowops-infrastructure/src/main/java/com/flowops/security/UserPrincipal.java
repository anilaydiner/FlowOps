package com.flowops.security;

import com.flowops.domain.identity.CallerIdentity;
import java.util.Set;

/**
 * The authenticated principal carried in the SecurityContext
 * after JWT validation.
 */
public record UserPrincipal(Long id, String username, Set<String> roles) {

    /**
     * Project this security principal into the framework-free
     * {@link CallerIdentity} consumed by application services and
     * domain policy. Dependency direction: security → domain.
     */
    public CallerIdentity toIdentity() {
        return new CallerIdentity(id, username, roles);
    }

    public static UserPrincipal fromAuthentication(org.springframework.security.core.Authentication authentication) {
        if (authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        throw new IllegalStateException("No authenticated principal in context");
    }

    public boolean hasRole(String roleCode) {
        return roles != null && roles.contains(roleCode);
    }
}
