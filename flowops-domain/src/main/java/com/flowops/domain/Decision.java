package com.flowops.domain;

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

/**
 * An immutable decision recorded on a step instance (PLAN §3).
 * Evidence carries the explainability payload (which role/permission matched).
 */
@Entity
@Table(name = "decisions")
public class Decision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_instance_id", nullable = false)
    private StepInstance stepInstance;

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
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private Map<String, Object> evidence = new LinkedHashMap<>();

    /** Set when action == DELEGATE: the user the approval was delegated to. */
    @Column(name = "delegated_to")
    private Long delegatedTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Decision() {
    }

    public Decision(Request request, StepInstance stepInstance, Long actorId,
                    String actorUsername, DecisionAction action,
                    String justification, Map<String, Object> evidence) {
        this(request, stepInstance, actorId, actorUsername, action, justification, evidence, null);
    }

    public Decision(Request request, StepInstance stepInstance, Long actorId,
                    String actorUsername, DecisionAction action,
                    String justification, Map<String, Object> evidence,
                    Long delegatedTo) {
        this.request = request;
        this.stepInstance = stepInstance;
        this.actorId = actorId;
        this.actorUsername = actorUsername;
        this.action = action;
        this.justification = justification;
        this.evidence = evidence != null ? new LinkedHashMap<>(evidence) : new LinkedHashMap<>();
        this.delegatedTo = delegatedTo;
        this.createdAt = Instant.now();
    }

    // ── Getters ────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Request getRequest() { return request; }
    public StepInstance getStepInstance() { return stepInstance; }
    public Long getActorId() { return actorId; }
    public String getActorUsername() { return actorUsername; }
    public DecisionAction getAction() { return action; }
    public String getJustification() { return justification; }
    public Map<String, Object> getEvidence() { return evidence; }
    public Long getDelegatedTo() { return delegatedTo; }
    public Instant getCreatedAt() { return createdAt; }
}
