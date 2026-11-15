package com.flowops.domain.identity;

import java.util.Set;

/**
 * Framework-free identity of the authenticated caller, as seen by
 * domain policy and application services.
 *
 * <p>Produced at the API edge (from the security principal) and passed
 * inward. The domain never depends on security types —
 * {@code com.flowops.security} may project into this, never the reverse.
 *
 * @param id       the user's ID
 * @param username the user's username (for logs and evidence trails)
 * @param roles    the role codes granted to the user (may be empty, never null)
 */
public record CallerIdentity(Long id, String username, Set<String> roles) {

    public CallerIdentity {
        if (id == null) {
            throw new IllegalArgumentException("CallerIdentity.id must not be null");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public CallerIdentity(Long id, String username) {
        this(id, username, Set.of());
    }

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }

    public boolean hasRole(String roleCode) {
        return roles.contains(roleCode);
    }
}
