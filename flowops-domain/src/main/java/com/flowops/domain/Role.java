package com.flowops.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named role (e.g. ADMIN, MEMBER) binding a set of permissions.
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code infra.persistence.entity.RoleEntity}; the rehydration
 * constructor and setters exist solely for that mapper.
 */
public class Role {

    private Long id;

    private String code;

    private String name;

    private String description;

    private Instant createdAt;

    private Set<Permission> permissions = new LinkedHashSet<>();

    public Role() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public Role(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public void setPermissions(Set<Permission> permissions) {
        this.permissions = permissions != null ? new LinkedHashSet<>(permissions) : new LinkedHashSet<>();
    }

    public Set<String> permissionCodes() {
        if (permissions == null) return Set.of();
        return permissions.stream().map(Permission::getCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<Permission> getPermissions() { return permissions; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setCode(String code) { this.code = code; }
    public void rehydrateName(String name) { this.name = name; }
    public void rehydrateDescription(String description) { this.description = description; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void rehydratePermissions(Set<Permission> permissions) { this.permissions = permissions; }
}
