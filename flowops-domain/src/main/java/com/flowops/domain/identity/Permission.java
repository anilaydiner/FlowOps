package com.flowops.domain.identity;

import java.time.Instant;

/**
 * An atomic permission from the catalog (PLAN §7).
 *
 * <p>
 * A permission may carry an optional scope constraint:
 * <ul>
 * <li>{@code scopeType = null} — global, no constraint</li>
 * <li>{@code scopeType = SELF} — only on the user's own resources</li>
 * <li>{@code scopeType = DEPARTMENT} — only in the user's department</li>
 * <li>{@code scopeType = REQUEST_TYPE} — only on a specific type</li>
 * <li>{@code scopeType = STEP} — only on a specific workflow step</li>
 * <li>{@code scopeType = THRESHOLD} — only up to a max amount</li>
 * </ul>
 *
 * <p>
 * The same {@code code} may appear multiple times with different scopes; the unique
 * constraint is on {@code (code, scopeType, scopeValue)}.
 *
 * <p>
 * Pure domain aggregate — no persistence annotations. The JPA mapping lives in
 * {@code persistence.entity.PermissionEntity}; the rehydration constructor and setters
 * exist solely for that mapper.
 */
public class Permission {

    private Long id;

    private String code;

    private String name;

    private String description;

    private ScopeType scopeType;

    private String scopeValue;

    private Instant createdAt;

    public Permission() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public Permission(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public Permission(String code, String name, String description, ScopeType scopeType, String scopeValue) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.scopeType = scopeType;
        this.scopeValue = scopeValue;
        this.createdAt = Instant.now();
    }

    public boolean isGlobal() {
        return scopeType == null;
    }

    public String scopeLabel() {
        if (scopeType == null)
            return "global";
        if (scopeValue != null)
            return scopeType.name() + ":" + scopeValue;
        return scopeType.name();
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public String getScopeValue() {
        return scopeValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setScopeType(ScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public void setScopeValue(String scopeValue) {
        this.scopeValue = scopeValue;
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) {
        this.id = id;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void rehydrateName(String name) {
        this.name = name;
    }

    public void rehydrateDescription(String description) {
        this.description = description;
    }

    public void rehydrateScopeType(ScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public void rehydrateScopeValue(String scopeValue) {
        this.scopeValue = scopeValue;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
