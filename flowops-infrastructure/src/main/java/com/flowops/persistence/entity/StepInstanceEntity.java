package com.flowops.persistence.entity;

import com.flowops.domain.request.StepState;
import com.flowops.domain.request.BlockReason;
import com.flowops.domain.workflow.CompletionRule;
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
 * JPA entity backing the {@link com.flowops.domain.request.StepInstance} aggregate
 * (table {@code step_instances}). Pure data carrier — all business behavior
 * lives on the domain aggregate; field mapping is identical to the schema.
 */
@Entity(name = "StepInstance")
@Table(name = "step_instances")
public class StepInstanceEntity {

    @Column(name="escalation_interval_minutes",nullable=false)
    private int escalationIntervalMinutes=60;
    @Column(name="max_escalation_levels")
    private Integer maxEscalationLevels;
    public int getEscalationIntervalMinutes() { return escalationIntervalMinutes; }
    public void setEscalationIntervalMinutes(int value) { this.escalationIntervalMinutes=value; }
    public Integer getMaxEscalationLevels() { return maxEscalationLevels; }
    public void setMaxEscalationLevels(Integer value) { this.maxEscalationLevels=value; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private RequestEntity request;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    private WorkflowStepEntity step;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private StepState state = StepState.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule", nullable = false, length = 8)
    private CompletionRule rule;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals;

    @Column(name = "condition")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> condition = new LinkedHashMap<>();

    @Column(name = "skip_reason", length = 200)
    private String skipReason;

    @Column(name = "blocked_reason", length = 200)
    private String blockedReason;

    @Column(name = "satisfied_by")
    private Long satisfiedBy;

    @Column(name = "satisfied_at")
    private Instant satisfiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "step_code", nullable = false)
    private String stepCode;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(name = "required_role", nullable = false)
    private String requiredRole;

    @Column(name = "sla_minutes")
    private Integer slaMinutes;

    @Column(name = "escalation_role")
    private String escalationRole;

    @Column(name = "roster_snapshotted", nullable = false)
    private boolean rosterSnapshotted;

    @Column(name = "roster_size")
    private Integer rosterSize;


    @Enumerated(EnumType.STRING)
    @Column(name = "block_reason_code")
    private BlockReason blockReasonCode;
    @Column(name = "blocked_at")
    private Instant blockedAt;
    @Column(name = "block_episode", nullable = false)
    private int blockEpisode;
    @Column(name = "recovery_owner_id")
    private Long recoveryOwnerId;
    @Column(name = "recovery_team")
    private String recoveryTeam;
    @Column(name = "recovered_at")
    private Instant recoveredAt;

    @Column(name = "activated_at")
    private Instant activatedAt;
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant value) { this.activatedAt = value; }

    @Column(name = "review_due_at")
    private Instant reviewDueAt;
    public Instant getReviewDueAt() { return reviewDueAt; }
    public void setReviewDueAt(Instant value) { this.reviewDueAt = value; }

    @Column(name = "next_escalation_at")
    private Instant nextEscalationAt;
    public Instant getNextEscalationAt() { return nextEscalationAt; }
    public void setNextEscalationAt(Instant value) { this.nextEscalationAt = value; }

    @Column(name = "expires_at")
    private Instant expiresAt;
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { this.expiresAt = value; }

    @Column(name = "escalation_level")
    private int escalationLevel;
    public int getEscalationLevel() { return escalationLevel; }
    public void setEscalationLevel(int value) { this.escalationLevel = value; }

    @Column(name="recovery_owner_revision",nullable=false)
    private int recoveryOwnerRevision;
    @Column(name="recovery_owner_unavailable",nullable=false)
    private boolean recoveryOwnerUnavailable;
    public int getRecoveryOwnerRevision() { return recoveryOwnerRevision; }
    public void setRecoveryOwnerRevision(int value) { this.recoveryOwnerRevision=value; }
    public boolean isRecoveryOwnerUnavailable() { return recoveryOwnerUnavailable; }
    public void setRecoveryOwnerUnavailable(boolean value) { this.recoveryOwnerUnavailable=value; }

    public StepInstanceEntity() {
        // JPA
    }

    public BlockReason getBlockReasonCode() { return blockReasonCode; }
    public void setBlockReasonCode(BlockReason value) { this.blockReasonCode = value; }

    public Instant getBlockedAt() { return blockedAt; }
    public void setBlockedAt(Instant value) { this.blockedAt = value; }

    public int getBlockEpisode() { return blockEpisode; }
    public void setBlockEpisode(int value) { this.blockEpisode = value; }

    public Long getRecoveryOwnerId() { return recoveryOwnerId; }
    public void setRecoveryOwnerId(Long value) { this.recoveryOwnerId = value; }

    public String getRecoveryTeam() { return recoveryTeam; }
    public void setRecoveryTeam(String value) { this.recoveryTeam = value; }

    public Instant getRecoveredAt() { return recoveredAt; }
    public void setRecoveredAt(Instant value) { this.recoveredAt = value; }

    // ── Getters / Setters ──────────────────────────────────────────────

    public String getStepCode() { return stepCode; }
    public void setStepCode(String stepCode) { this.stepCode = stepCode; }
    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }
    public String getRequiredRole() { return requiredRole; }
    public void setRequiredRole(String requiredRole) { this.requiredRole = requiredRole; }
    public Integer getSlaMinutes() { return slaMinutes; }
    public void setSlaMinutes(Integer slaMinutes) { this.slaMinutes = slaMinutes; }
    public String getEscalationRole() { return escalationRole; }
    public void setEscalationRole(String escalationRole) { this.escalationRole = escalationRole; }
    public boolean isRosterSnapshotted() { return rosterSnapshotted; }
    public void setRosterSnapshotted(boolean rosterSnapshotted) { this.rosterSnapshotted = rosterSnapshotted; }
    public Integer getRosterSize() { return rosterSize; }
    public void setRosterSize(Integer rosterSize) { this.rosterSize = rosterSize; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RequestEntity getRequest() { return request; }
    public void setRequest(RequestEntity request) { this.request = request; }

    public WorkflowStepEntity getStep() { return step; }
    public void setStep(WorkflowStepEntity step) { this.step = step; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public StepState getState() { return state; }
    public void setState(StepState state) { this.state = state; }

    public CompletionRule getRule() { return rule; }
    public void setRule(CompletionRule rule) { this.rule = rule; }

    public int getRequiredApprovals() { return requiredApprovals; }
    public void setRequiredApprovals(int requiredApprovals) { this.requiredApprovals = requiredApprovals; }

    public Map<String, Object> getCondition() { return condition; }
    public void setCondition(Map<String, Object> condition) { this.condition = condition; }

    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }

    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }

    public Long getSatisfiedBy() { return satisfiedBy; }
    public void setSatisfiedBy(Long satisfiedBy) { this.satisfiedBy = satisfiedBy; }

    public Instant getSatisfiedAt() { return satisfiedAt; }
    public void setSatisfiedAt(Instant satisfiedAt) { this.satisfiedAt = satisfiedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
