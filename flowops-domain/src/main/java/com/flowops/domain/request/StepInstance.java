package com.flowops.domain.request;

import com.flowops.domain.JsonObjects;
import com.flowops.domain.exception.InvalidStateTransitionException;
import com.flowops.domain.workflow.CompletionRule;
import com.flowops.domain.workflow.WorkflowStep;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A workflow step materialized for a specific request (PLAN §3). The engine advances a
 * request by satisfying/skipping these in position order.
 *
 * <p>
 * Pure domain aggregate — no persistence annotations. The JPA mapping lives in
 * {@code persistence.entity.StepInstanceEntity}; the rehydration constructor and setters
 * exist solely for that mapper.
 */
public class StepInstance {

    private Long id;

    private Request request;

    private WorkflowStep step;

    private int position;

    private String stepCode;

    private String stepName;

    private String requiredRole;

    private Integer slaMinutes;

    private String escalationRole;

    private boolean rosterSnapshotted = true;

    private Integer rosterSize;

    private StepState state = StepState.PENDING;

    private CompletionRule rule;

    private int requiredApprovals;

    private Map<String, Object> condition = new LinkedHashMap<>();

    private String skipReason;

    private String blockedReason;

    private BlockReason blockReasonCode;

    private Instant blockedAt;

    private int blockEpisode;

    private Long recoveryOwnerId;

    private int recoveryOwnerRevision;

    private boolean recoveryOwnerUnavailable;

    private String recoveryTeam;

    private Instant recoveredAt;

    private Long satisfiedBy;

    private Instant satisfiedAt;

    private Instant createdAt;

    private Instant activatedAt;

    private Instant reviewDueAt;

    private Instant nextEscalationAt;

    private Instant expiresAt;

    private int escalationLevel;

    private int escalationIntervalMinutes = 60;

    private Integer maxEscalationLevels;

    public StepInstance() {
        // Rehydration only — used by the persistence mapper, not by business code.
    }

    public StepInstance(Request request, WorkflowStep step) {
        this(request, step, Instant.now());
    }

    public StepInstance(Request request, WorkflowStep step, Instant activatedAt) {
        this.request = request;
        this.step = step;
        this.position = step.getPosition();
        this.stepCode = step.getCode();
        this.stepName = step.getName();
        this.requiredRole = step.getRequiredRole();
        this.slaMinutes = step.getSlaMinutes();
        this.escalationIntervalMinutes = step.getEscalationIntervalMinutes();
        this.maxEscalationLevels = step.getMaxEscalationLevels();
        this.escalationRole = step.getEscalationRole();
        this.rule = step.getCompletionRule();
        this.requiredApprovals = step.getRequiredApprovals();
        this.condition = JsonObjects.immutableCopy(step.getCondition());
        this.state = StepState.PENDING;
        this.createdAt = activatedAt;
        this.activatedAt = activatedAt;
        this.reviewDueAt = slaMinutes == null ? null : activatedAt.plus(Duration.ofMinutes(slaMinutes));
        this.nextEscalationAt = reviewDueAt;
        this.expiresAt = step.getExpiryMinutes() == null ? null
                : activatedAt.plus(Duration.ofMinutes(step.getExpiryMinutes()));
    }

    /** Freeze the electorate size once, before this instance is persisted. */
    public void snapshotRoster(int size) {
        requirePending("snapshot roster");
        if (id != null || rosterSize != null || size < 0) {
            throw new IllegalStateException("Participant roster is already frozen or invalid");
        }
        this.rosterSize = size;
        if (rule == CompletionRule.ALL_OF)
            this.requiredApprovals = Math.max(1, size);
    }

    /** PENDING → SKIPPED (conditional step does not apply). */
    public void skip(String reason) {
        requirePending("skip");
        this.state = StepState.SKIPPED;
        this.skipReason = reason;
    }

    /** PENDING → BLOCKED. Only a new episode changes the original blocked timestamp. */
    public void block(BlockReason reason, String detail, Long ownerId, String team, Instant now) {
        requirePending("block");
        if (reason == null || team == null || team.isBlank() || now == null) {
            throw new IllegalArgumentException("Blocking requires a reason, recovery team and occurrence time");
        }
        this.state = StepState.BLOCKED;
        this.blockedReason = detail;
        this.blockReasonCode = reason;
        this.blockedAt = now;
        this.blockEpisode++;
        this.recoveryOwnerId = ownerId;
        this.recoveryOwnerRevision = 0;
        this.recoveryOwnerUnavailable = ownerId == null;
        this.recoveryTeam = team;
        this.recoveredAt = null;
        this.escalationLevel = 0;
        Instant blockedDue = now.plus(Duration.ofMinutes(escalationIntervalMinutes));
        this.nextEscalationAt = reviewDueAt == null || blockedDue.isBefore(reviewDueAt) ? blockedDue : reviewDueAt;
    }

    /**
     * Ownership changes do not start another block episode or alter approval
     * policy/deadlines.
     */
    public void reassignRecoveryOwner(Long ownerId, String team) {
        if (state != StepState.BLOCKED)
            throw new InvalidStateTransitionException("step instance", state, "reassign recovery owner");
        if (team == null || team.isBlank())
            throw new IllegalArgumentException("Recovery team is required");
        this.recoveryOwnerId = ownerId;
        this.recoveryTeam = team;
        this.recoveryOwnerUnavailable = ownerId == null;
        this.recoveryOwnerRevision++;
    }

    public int getRecoveryOwnerRevision() {
        return recoveryOwnerRevision;
    }

    public void setRecoveryOwnerRevision(int value) {
        this.recoveryOwnerRevision = value;
    }

    public boolean isRecoveryOwnerUnavailable() {
        return recoveryOwnerUnavailable;
    }

    public void setRecoveryOwnerUnavailable(boolean value) {
        this.recoveryOwnerUnavailable = value;
    }

    /** BLOCKED → PENDING; keep votes, frozen policy and activation time unchanged. */
    public void recover(Instant now) {
        if (state != StepState.BLOCKED)
            throw new InvalidStateTransitionException("step instance", state, "recover");
        this.state = StepState.PENDING;
        this.recoveredAt = java.util.Objects.requireNonNull(now);
        if (reviewDueAt == null || escalationLimitReached())
            this.nextEscalationAt = null;
        else if (now.isBefore(reviewDueAt))
            this.nextEscalationAt = reviewDueAt;
    }

    public void reject() {
        requirePending("reject");
        this.state = StepState.REJECTED;
    }

    public void cancel() {
        if (isTerminal())
            throw new InvalidStateTransitionException("step instance", state, "cancel");
        this.state = StepState.CANCELLED;
    }

    /** PENDING → SATISFIED (completion rule met). */
    public void satisfy(Long actorId) {
        requirePending("satisfy");
        this.state = StepState.SATISFIED;
        this.satisfiedBy = actorId;
        this.satisfiedAt = Instant.now();
    }

    /** A blocked step is active and may recover or be stopped. */
    public boolean isTerminal() {
        return this.state != StepState.PENDING && this.state != StepState.BLOCKED;
    }

    // ── SLA policy (PLAN §8, iteration 7) ──────────────────────────────

    public boolean hasSla() {
        return reviewDueAt != null;
    }

    public Instant slaDeadline() {
        return reviewDueAt;
    }

    public boolean isSlaBreached(Instant now) {
        return reviewDueAt != null && !now.isBefore(reviewDueAt);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public void requireUnexpired(Instant now) {
        if (isExpired(now))
            throw new com.flowops.domain.exception.ConflictException("Business review deadline has passed");
    }

    private boolean escalationLimitReached() {
        return escalationLevel == Integer.MAX_VALUE
                || maxEscalationLevels != null && escalationLevel >= maxEscalationLevels;
    }

    public boolean escalationDue(Instant now) {
        return !isTerminal() && !isExpired(now) && !escalationLimitReached() && nextEscalationAt != null
                && !now.isBefore(nextEscalationAt);
    }

    public void recordEscalation(Instant now) {
        if (!escalationDue(now))
            throw new InvalidStateTransitionException("step instance", state, "escalate before due or beyond limit");
        escalationLevel++;
        nextEscalationAt = escalationLimitReached() ? null : now.plus(Duration.ofMinutes(escalationIntervalMinutes));
    }

    /**
     * An unavailable recipient set does not consume a level or spin every worker tick.
     */
    public void deferEscalation(Instant now) {
        if (!escalationDue(now))
            throw new InvalidStateTransitionException("step instance", state, "defer escalation");
        nextEscalationAt = now.plus(Duration.ofMinutes(escalationIntervalMinutes));
    }

    public int getEscalationIntervalMinutes() {
        return escalationIntervalMinutes;
    }

    public void setEscalationIntervalMinutes(int value) {
        this.escalationIntervalMinutes = value;
    }

    public Integer getMaxEscalationLevels() {
        return maxEscalationLevels;
    }

    public void setMaxEscalationLevels(Integer value) {
        this.maxEscalationLevels = value;
    }

    /** The role escalated on breach (falls back to the step's required role). */
    public String escalationRole() {
        return escalationRole != null ? escalationRole : requiredRole;
    }

    private void requirePending(String operation) {
        if (state != StepState.PENDING) {
            throw new InvalidStateTransitionException("step instance", state, operation);
        }
    }

    // ── Getters / Setters ──────────────────────────────────────────

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant value) {
        this.activatedAt = value;
    }

    public Instant getReviewDueAt() {
        return reviewDueAt;
    }

    public void setReviewDueAt(Instant value) {
        this.reviewDueAt = value;
    }

    public Instant getNextEscalationAt() {
        return nextEscalationAt;
    }

    public void setNextEscalationAt(Instant value) {
        this.nextEscalationAt = value;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant value) {
        this.expiresAt = value;
    }

    public int getEscalationLevel() {
        return escalationLevel;
    }

    public void setEscalationLevel(int value) {
        this.escalationLevel = value;
    }

    public Long getId() {
        return id;
    }

    public Request getRequest() {
        return request;
    }

    public WorkflowStep getStep() {
        return step;
    }

    public String getStepCode() {
        return stepCode;
    }

    public String getStepName() {
        return stepName;
    }

    public String getRequiredRole() {
        return requiredRole;
    }

    public Integer getSlaMinutes() {
        return slaMinutes;
    }

    public String getEscalationRole() {
        return escalationRole;
    }

    public boolean isRosterSnapshotted() {
        return rosterSnapshotted;
    }

    public Integer getRosterSize() {
        return rosterSize;
    }

    public int getPosition() {
        return position;
    }

    public StepState getState() {
        return state;
    }

    public CompletionRule getRule() {
        return rule;
    }

    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    public Map<String, Object> getCondition() {
        return condition;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public BlockReason getBlockReasonCode() {
        return blockReasonCode;
    }

    public Instant getBlockedAt() {
        return blockedAt;
    }

    public int getBlockEpisode() {
        return blockEpisode;
    }

    public Long getRecoveryOwnerId() {
        return recoveryOwnerId;
    }

    public String getRecoveryTeam() {
        return recoveryTeam;
    }

    public Instant getRecoveredAt() {
        return recoveredAt;
    }

    public Long getSatisfiedBy() {
        return satisfiedBy;
    }

    public Instant getSatisfiedAt() {
        return satisfiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // ── Setters (persistence rehydration only) ─────────────────────────

    public void setStepCode(String stepCode) {
        this.stepCode = stepCode;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public void setRequiredRole(String requiredRole) {
        this.requiredRole = requiredRole;
    }

    public void setSlaMinutes(Integer slaMinutes) {
        this.slaMinutes = slaMinutes;
    }

    public void setEscalationRole(String escalationRole) {
        this.escalationRole = escalationRole;
    }

    public void setRosterSnapshotted(boolean rosterSnapshotted) {
        this.rosterSnapshotted = rosterSnapshotted;
    }

    public void setRosterSize(Integer rosterSize) {
        this.rosterSize = rosterSize;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public void setStep(WorkflowStep step) {
        this.step = step;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public void setState(StepState state) {
        this.state = state;
    }

    public void setRule(CompletionRule rule) {
        this.rule = rule;
    }

    public void setRequiredApprovals(int requiredApprovals) {
        this.requiredApprovals = requiredApprovals;
    }

    public void setCondition(Map<String, Object> condition) {
        this.condition = JsonObjects.immutableCopy(condition);
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public void setBlockReasonCode(BlockReason value) {
        this.blockReasonCode = value;
    }

    public void setBlockedAt(Instant value) {
        this.blockedAt = value;
    }

    public void setBlockEpisode(int value) {
        this.blockEpisode = value;
    }

    public void setRecoveryOwnerId(Long value) {
        this.recoveryOwnerId = value;
    }

    public void setRecoveryTeam(String value) {
        this.recoveryTeam = value;
    }

    public void setRecoveredAt(Instant value) {
        this.recoveredAt = value;
    }

    public void setSatisfiedBy(Long satisfiedBy) {
        this.satisfiedBy = satisfiedBy;
    }

    public void setSatisfiedAt(Instant satisfiedAt) {
        this.satisfiedAt = satisfiedAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
