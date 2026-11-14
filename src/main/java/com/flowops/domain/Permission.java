package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An atomic permission from the catalog (PLAN §7).
 *
 * <p>A permission may carry an optional scope constraint:
 * <ul>
 *   <li>{@code scopeType = null} — global, no constraint</li>
 *   <li>{@code scopeType = SELF} — only on the user's own resources</li>
 *   <li>{@code scopeType = DEPARTMENT} — only in the user's department</li>
 *   <li>{@code scopeType = REQUEST_TYPE} — only on a specific type</li>
 *   <li>{@code scopeType = STEP} — only on a specific workflow step</li>
 *   <li>{@code scopeType = THRESHOLD} — only up to a max amount</li>
 * </ul>
 *
 * <p>The same {@code code} may appear multiple times with different scopes;
 * the unique constraint is on {@code (code, scopeType, scopeValue)}.
 */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", length = 32)
    private ScopeType scopeType;

    @Column(name = "scope_value", length = 128)
    private String scopeValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Permission() {
    }

    public Permission(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public Permission(String code, String name, String description,
                      ScopeType scopeType, String scopeValue) {
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
        if (scopeType == null) return "global";
        if (scopeValue != null) return scopeType.name() + ":" + scopeValue;
        return scopeType.name();
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ScopeType getScopeType() { return scopeType; }
    public String getScopeValue() { return scopeValue; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setScopeType(ScopeType scopeType) { this.scopeType = scopeType; }
    public void setScopeValue(String scopeValue) { this.scopeValue = scopeValue; }
}
