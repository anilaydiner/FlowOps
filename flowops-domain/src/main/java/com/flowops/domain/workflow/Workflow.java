package com.flowops.domain.workflow;

import java.time.Instant;

/**
 * A named, code-identified approval workflow.
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code persistence.entity.WorkflowEntity}; the rehydration
 * constructor and setters exist solely for that mapper.
 */
public class Workflow {

    private Long id;

    private String code;

    private String name;

    private String description;

    private boolean active = true;

    private Instant createdAt;

    private Instant updatedAt;

    public Workflow() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public Workflow(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateProfile(String name, String description) {
        this.name = name;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    // ── Getters / Setters ──────────────────────────────────────────

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setCode(String code) { this.code = code; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void rehydrateActive(boolean active) { this.active = active; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
