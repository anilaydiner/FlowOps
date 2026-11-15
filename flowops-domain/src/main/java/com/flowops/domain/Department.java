package com.flowops.domain;

import java.time.Instant;

/**
 * An organizational department (e.g. a cost center or business unit).
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code infra.persistence.entity.DepartmentEntity}; the rehydration
 * constructor and setters exist solely for that mapper.
 */
public class Department {

    private Long id;

    private String code;

    private String name;

    private Instant createdAt;

    public Department() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public Department(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setCode(String code) { this.code = code; }
    public void setName(String name) { this.name = name; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
