package com.flowops.security;

import java.util.Set;

/**
 * The authenticated principal carried in the SecurityContext
 * after JWT validation.
 */
public record UserPrincipal(Long id, String username, Set<String> roles) {

    public static UserPrincipal fromAuthentication(org.springframework.security.core.Authentication authentication) {
        if (authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        throw new IllegalStateException("No authenticated principal in context");
    }

    public boolean isAdmin() {
        return roles != null && roles.contains("ADMIN");
    }

    public boolean hasRole(String roleCode) {
        return roles != null && roles.contains(roleCode);
    }
}
