package com.flowops.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA entity backing the {@link com.flowops.domain.RequestType} aggregate
 * (table {@code request_types}). Pure data carrier — all business behavior
 * lives on the domain aggregate; field mapping is identical to the schema.
 */
@Entity(name = "RequestType")
@Table(name = "request_types")
public class RequestTypeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    private String description;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "owning_dept_id")
    private DepartmentEntity owningDepartment;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "default_workflow_id")
    private WorkflowEntity defaultWorkflow;

    /** Required payload fields: field name → human-readable description. */
    @Column(name = "required_fields", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> requiredFields = new LinkedHashMap<>();

    /** Validation rules: field name → rule object (e.g. {"type":"number","min":0.01}). */
    @Column(name = "validation_rules", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> validationRules = new LinkedHashMap<>();

    /** Execution actions triggered on APPROVED (e.g. ["PROVISIONING"]). */
    @Column(name = "execution_actions", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> executionActions = new ArrayList<>();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RequestTypeEntity() {
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

    public DepartmentEntity getOwningDepartment() { return owningDepartment; }
    public void setOwningDepartment(DepartmentEntity owningDepartment) { this.owningDepartment = owningDepartment; }

    public WorkflowEntity getDefaultWorkflow() { return defaultWorkflow; }
    public void setDefaultWorkflow(WorkflowEntity defaultWorkflow) { this.defaultWorkflow = defaultWorkflow; }

    public Map<String, Object> getRequiredFields() { return requiredFields; }
    public void setRequiredFields(Map<String, Object> requiredFields) { this.requiredFields = requiredFields; }

    public Map<String, Object> getValidationRules() { return validationRules; }
    public void setValidationRules(Map<String, Object> validationRules) { this.validationRules = validationRules; }

    public List<String> getExecutionActions() { return executionActions; }
    public void setExecutionActions(List<String> executionActions) { this.executionActions = executionActions; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
