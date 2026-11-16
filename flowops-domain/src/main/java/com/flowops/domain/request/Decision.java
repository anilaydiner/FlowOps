package com.flowops.domain.request;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable decision recorded on a step instance (PLAN §3).
 * Evidence carries the explainability payload (which role/permission matched).
 *
 * <p>Pure domain aggregate — no persistence annotations. The JPA mapping
 * lives in {@code persistence.entity.DecisionEntity}; the
 * rehydration constructor and setters exist solely for that mapper.
 */
public class Decision {

    private Long id;

    private Request request;

    private StepInstance stepInstance;

    private Long actorId;

    private String actorUsername;

    private DecisionAction action;

    private String justification;

    private Map<String, Object> evidence = new LinkedHashMap<>();

    /** Set when action == DELEGATE: the user the approval was delegated to. */
    private Long delegatedTo;

    private Instant createdAt;

    public Decision() {
        // Rehydration only — used by the persistence mapper, not by business code.
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

    // ── Getters ────────────────────────────────────────────

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

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setId(Long id) { this.id = id; }
    public void setRequest(Request request) { this.request = request; }
    public void setStepInstance(StepInstance stepInstance) { this.stepInstance = stepInstance; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public void setActorUsername(String actorUsername) { this.actorUsername = actorUsername; }
    public void setAction(DecisionAction action) { this.action = action; }
    public void setJustification(String justification) { this.justification = justification; }
    public void setEvidence(Map<String, Object> evidence) { this.evidence = evidence; }
    public void setDelegatedTo(Long delegatedTo) { this.delegatedTo = delegatedTo; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
