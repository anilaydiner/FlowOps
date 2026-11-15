package com.flowops.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A user account — authentication principal and authorization holder
 * (roles, active flag, password hash).
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code infra.persistence.entity.UserEntity}; the rehydration
 * constructor and setters exist solely for that mapper.
 */
public class User {

    private Long id;

    private String username;

    private String displayName;

    private String email;

    private Department department;

    /** Roles assigned to this user (e.g. ADMIN, MEMBER). */
    private Set<Role> roles = new LinkedHashSet<>();

    private String passwordHash;

    private boolean active = true;

    private Instant createdAt;

    private Instant updatedAt;

    public User() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public static User newUser(String username,
                               String displayName,
                               String email,
                               Department department,
                               Set<Role> roles,
                               String passwordHash) {
        User user = new User();
        user.username = username;
        user.displayName = displayName;
        user.email = email;
        user.department = department;
        user.roles = roles != null ? new LinkedHashSet<>(roles) : new LinkedHashSet<>();
        user.passwordHash = passwordHash;
        user.active = true;
        user.createdAt = Instant.now();
        user.updatedAt = user.createdAt;
        return user;
    }

    public void updateProfile(String displayName, String email, Department department) {
        this.displayName = displayName;
        this.email = email;
        this.department = department;
        this.updatedAt = Instant.now();
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles != null ? new LinkedHashSet<>(roles) : new LinkedHashSet<>();
        this.updatedAt = Instant.now();
    }

    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public boolean hasRole(String roleCode) {
        return roles != null && roles.stream().anyMatch(r -> r.getCode().equals(roleCode));
    }

    /** Convenience: role codes as a sorted list. */
    public Set<String> roleCodes() {
        if (roles == null) return Set.of();
        return roles.stream().map(Role::getCode).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public Department getDepartment() { return department; }
    public Set<Role> getRoles() { return roles; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setUsername(String username) { this.username = username; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEmail(String email) { this.email = email; }
    public void setDepartment(Department department) { this.department = department; }
    public void rehydrateRoles(Set<Role> roles) { this.roles = roles; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void rehydrateActive(boolean active) { this.active = active; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
