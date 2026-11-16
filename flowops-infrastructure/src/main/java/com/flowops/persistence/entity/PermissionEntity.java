package com.flowops.persistence.entity;

import com.flowops.domain.identity.ScopeType;
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
 * JPA entity backing the {@link com.flowops.domain.identity.Permission} aggregate
 * (table {@code permissions}). Pure data carrier — all business behavior
 * lives on the domain aggregate.
 */
@Entity(name = "Permission")
@Table(name = "permissions")
public class PermissionEntity {

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

    public PermissionEntity() {
        // JPA
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ScopeType getScopeType() { return scopeType; }
    public void setScopeType(ScopeType scopeType) { this.scopeType = scopeType; }

    public String getScopeValue() { return scopeValue; }
    public void setScopeValue(String scopeValue) { this.scopeValue = scopeValue; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
