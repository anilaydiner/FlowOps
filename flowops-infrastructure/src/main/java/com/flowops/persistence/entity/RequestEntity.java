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
