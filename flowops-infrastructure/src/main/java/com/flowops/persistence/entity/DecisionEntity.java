package com.flowops.persistence.entity;

import com.flowops.domain.request.DecisionAction;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity backing the {@link com.flowops.domain.request.Decision} aggregate
 * (table {@code decisions}). Pure data carrier — all business behavior
 * lives on the domain aggregate; field mapping is identical to the schema.
 */
@Entity(name = "Decision")
@Table(name = "decisions")
public class DecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private RequestEntity request;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "step_instance_id", nullable = false)
    private StepInstanceEntity stepInstance;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "actor_username", length = 100)
    private String actorUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DecisionAction action;

    @Column(name = "justification", length = 1000)
    private String justification;

    @Column(name = "evidence")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> evidence = new LinkedHashMap<>();

    /** Set when action == DELEGATE: the user the approval was delegated to. */
    @Column(name = "delegated_to")
    private Long delegatedTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public DecisionEntity() {
        // JPA
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RequestEntity getRequest() { return request; }
    public void setRequest(RequestEntity request) { this.request = request; }

    public StepInstanceEntity getStepInstance() { return stepInstance; }
    public void setStepInstance(StepInstanceEntity stepInstance) { this.stepInstance = stepInstance; }

    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }

    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String actorUsername) { this.actorUsername = actorUsername; }

    public DecisionAction getAction() { return action; }
    public void setAction(DecisionAction action) { this.action = action; }

    public String getJustification() { return justification; }
    public void setJustification(String justification) { this.justification = justification; }

    public Map<String, Object> getEvidence() { return evidence; }
    public void setEvidence(Map<String, Object> evidence) { this.evidence = evidence; }

    public Long getDelegatedTo() { return delegatedTo; }
    public void setDelegatedTo(Long delegatedTo) { this.delegatedTo = delegatedTo; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
