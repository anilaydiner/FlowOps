package com.flowops.persistence.entity;

import com.flowops.domain.request.RequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity backing the {@link com.flowops.domain.request.Request} aggregate
 * (table {@code requests}). Pure data carrier — all business behavior lives
 * on the domain aggregate; field mapping is identical to the schema.
 */
@Entity(name = "Request")
@Table(name = "requests")
public class RequestEntity {

    @Column(name="legacy_plan_disposition_id")
    private java.util.UUID legacyPlanDispositionId;
    public java.util.UUID getLegacyPlanDispositionId(){return legacyPlanDispositionId;}
    public void setLegacyPlanDispositionId(java.util.UUID value){legacyPlanDispositionId=value;}

    @Column(name="execution_plan_id")
    private java.util.UUID executionPlanId;
    public java.util.UUID getExecutionPlanId(){return executionPlanId;}
    public void setExecutionPlanId(java.util.UUID value){executionPlanId=value;}
    @Column(name="execution_planned_at")
    private Instant executionPlannedAt;
    public Instant getExecutionPlannedAt() { return executionPlannedAt; }
    public void setExecutionPlannedAt(Instant value) { this.executionPlannedAt=value; }
    @Column(name="execution_started_at")
    private Instant executionStartedAt;
    public Instant getExecutionStartedAt() { return executionStartedAt; }
    public void setExecutionStartedAt(Instant value) { this.executionStartedAt=value; }
    @Column(name="execution_start_unknown")
    private boolean executionStartUnknown;
    public boolean isExecutionStartUnknown() { return executionStartUnknown; }
    public void setExecutionStartUnknown(boolean value) { this.executionStartUnknown=value; }
    @Column(name="execution_start_inferred")
    private boolean executionStartInferred;
    public boolean isExecutionStartInferred() { return executionStartInferred; }
    public void setExecutionStartInferred(boolean value) { this.executionStartInferred=value; }

    @Enumerated(EnumType.STRING)
    @Column(name="outcome")
    private com.flowops.domain.request.RequestOutcome outcome;
    public com.flowops.domain.request.RequestOutcome getOutcome(){return outcome;}
    public void setOutcome(com.flowops.domain.request.RequestOutcome value){outcome=value;}
    @Column(name="approved_at")
    private Instant approvedAt;
    public Instant getApprovedAt(){return approvedAt;}
    public void setApprovedAt(Instant value){approvedAt=value;}
    @Column(name="rejected_at")
    private Instant rejectedAt;
    public Instant getRejectedAt(){return rejectedAt;}
    public void setRejectedAt(Instant value){rejectedAt=value;}
    @Column(name="executed_at")
    private Instant executedAt;
    public Instant getExecutedAt(){return executedAt;}
    public void setExecutedAt(Instant value){executedAt=value;}
    @Column(name="cancelled_at")
    private Instant cancelledAt;
    public Instant getCancelledAt(){return cancelledAt;}
    public void setCancelledAt(Instant value){cancelledAt=value;}
    @Column(name="expired_at")
    private Instant expiredAt;
    public Instant getExpiredAt(){return expiredAt;}
    public void setExpiredAt(Instant value){expiredAt=value;}
    @Column(name="closed_at")
    private Instant closedAt;
    public Instant getClosedAt(){return closedAt;}
    public void setClosedAt(Instant value){closedAt=value;}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private RequestTypeEntity type;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private UserEntity requester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RequestStatus status = RequestStatus.DRAFT;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "correlation_id", length = 36)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    public RequestEntity() {
        // JPA
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RequestTypeEntity getType() { return type; }
    public void setType(RequestTypeEntity type) { this.type = type; }

    public UserEntity getRequester() { return requester; }
    public void setRequester(UserEntity requester) { this.requester = requester; }

    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
}
