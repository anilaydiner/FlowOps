package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A request type (e.g. EXPENSE, IT_ACCESS) with JSONB-driven
 * per-type field requirements and validation rules.
 */
@Entity
@Table(name = "request_types")
public class RequestType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owning_dept_id")
    private Department owningDepartment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_workflow_id")
    private Workflow defaultWorkflow;

    /** Required payload fields: field name → human-readable description. */
    @Column(name = "required_fields", nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private Map<String, Object> requiredFields = new LinkedHashMap<>();

    /** Validation rules: field name → rule object (e.g. {"type":"number","min":0.01}). */
    @Column(name = "validation_rules", nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private Map<String, Object> validationRules = new LinkedHashMap<>();

    /** Execution actions triggered on APPROVED (e.g. ["PROVISIONING"]). */
    @Column(name = "execution_actions", nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private List<String> executionActions = new java.util.ArrayList<>();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RequestType() {
    }

    public RequestType(String code, String name, String description, Department owningDepartment) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.owningDepartment = owningDepartment;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateProfile(String name, String description, Department owningDepartment) {
        this.name = name;
        this.description = description;
        if (owningDepartment != null) {
            this.owningDepartment = owningDepartment;
        }
        this.updatedAt = Instant.now();
    }

    public void setRequiredFields(Map<String, Object> requiredFields) {
        this.requiredFields = requiredFields != null ? new LinkedHashMap<>(requiredFields) : new LinkedHashMap<>();
        this.updatedAt = Instant.now();
    }

    public void setValidationRules(Map<String, Object> validationRules) {
        this.validationRules = validationRules != null ? new LinkedHashMap<>(validationRules) : new LinkedHashMap<>();
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Department getOwningDepartment() { return owningDepartment; }
    public Workflow getDefaultWorkflow() { return defaultWorkflow; }
    public Long getDefaultWorkflowId() {
        Workflow wf = getDefaultWorkflow();
        return wf == null ? null : wf.getId();
    }
    public void setDefaultWorkflow(Workflow workflow) {
        this.defaultWorkflow = workflow;
        this.updatedAt = Instant.now();
    }
    public Map<String, Object> getRequiredFields() { return requiredFields; }
    public Map<String, Object> getValidationRules() { return validationRules; }
    public List<String> getExecutionActions() { return executionActions; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
