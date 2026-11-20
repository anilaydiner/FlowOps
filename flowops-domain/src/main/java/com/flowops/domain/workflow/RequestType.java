package com.flowops.domain.workflow;

import com.flowops.domain.organization.Department;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A request type (e.g. EXPENSE, IT_ACCESS) with JSONB-driven per-type field requirements
 * and validation rules.
 *
 * <p>
 * Pure domain aggregate — no persistence annotations. The JPA mapping lives in
 * {@code persistence.entity.RequestTypeEntity}; the rehydration constructor and setters
 * exist solely for that mapper.
 */
public class RequestType {

    private Long id;

    private String code;

    private String name;

    private String description;

    private Department owningDepartment;

    private Workflow defaultWorkflow;

    /** Required payload fields: field name → human-readable description. */
    private Map<String, Object> requiredFields = new LinkedHashMap<>();

    /** Validation rules: field name → rule object (e.g. {"type":"number","min":0.01}). */
    private Map<String, Object> validationRules = new LinkedHashMap<>();

    /** Execution actions triggered on APPROVED (e.g. ["PROVISIONING"]). */
    private List<String> executionActions = new java.util.ArrayList<>();

    private boolean active = true;

    private Instant createdAt;

    private Instant updatedAt;

    public RequestType() {
        // Rehydration only — used by the persistence mapper, not by business code.
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

    public Department getOwningDepartment() {
        return owningDepartment;
    }

    public Workflow getDefaultWorkflow() {
        return defaultWorkflow;
    }

    public Long getDefaultWorkflowId() {
        Workflow wf = getDefaultWorkflow();
        return wf == null ? null : wf.getId();
    }

    public void setDefaultWorkflow(Workflow workflow) {
        this.defaultWorkflow = workflow;
        this.updatedAt = Instant.now();
    }

    public Map<String, Object> getRequiredFields() {
        return requiredFields;
    }

    public Map<String, Object> getValidationRules() {
        return validationRules;
    }

    public List<String> getExecutionActions() {
        return executionActions;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) {
        this.id = id;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setOwningDepartment(Department owningDepartment) {
        this.owningDepartment = owningDepartment;
    }

    public void rehydrateDefaultWorkflow(Workflow workflow) {
        this.defaultWorkflow = workflow;
    }

    public void rehydrateRequiredFields(Map<String, Object> requiredFields) {
        this.requiredFields = requiredFields;
    }

    public void rehydrateValidationRules(Map<String, Object> validationRules) {
        this.validationRules = validationRules;
    }

    public void setExecutionActions(List<String> executionActions) {
        this.executionActions = executionActions;
    }

    public void rehydrateActive(boolean active) {
        this.active = active;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

}
