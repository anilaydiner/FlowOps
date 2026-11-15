package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 255, unique = true)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /** Roles assigned to this user (e.g. ADMIN, MEMBER). */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new LinkedHashSet<>();

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
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
}
