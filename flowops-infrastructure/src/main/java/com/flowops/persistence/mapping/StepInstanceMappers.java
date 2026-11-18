package com.flowops.persistence.mapping;

import com.flowops.domain.request.StepInstance;
import com.flowops.persistence.entity.StepInstanceEntity;

/**
 * Maps the {@link StepInstance} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class StepInstanceMappers {

    private StepInstanceMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static StepInstanceEntity toEntity(StepInstance d) {
        if (d == null) {
            return null;
        }
        StepInstanceEntity e = new StepInstanceEntity();
        e.setId(d.getId());
        e.setRequest(RequestMappers.toEntity(d.getRequest()));
        e.setStep(WorkflowStepMappers.toEntity(d.getStep()));
        e.setPosition(d.getPosition());
        e.setStepCode(d.getStepCode());
        e.setStepName(d.getStepName());
        e.setRequiredRole(d.getRequiredRole());
        e.setSlaMinutes(d.getSlaMinutes());
        e.setEscalationIntervalMinutes(d.getEscalationIntervalMinutes());
        e.setMaxEscalationLevels(d.getMaxEscalationLevels());
        e.setEscalationRole(d.getEscalationRole());
        e.setRosterSnapshotted(d.isRosterSnapshotted());
        e.setRosterSize(d.getRosterSize());

        e.setState(d.getState());
        e.setRule(d.getRule());
        e.setRequiredApprovals(d.getRequiredApprovals());
        e.setCondition(d.getCondition());
        e.setSkipReason(d.getSkipReason());
        e.setBlockedReason(d.getBlockedReason());
        e.setBlockReasonCode(d.getBlockReasonCode());
        e.setBlockedAt(d.getBlockedAt());
        e.setBlockEpisode(d.getBlockEpisode());
        e.setRecoveryOwnerId(d.getRecoveryOwnerId());
        e.setRecoveryOwnerRevision(d.getRecoveryOwnerRevision());
        e.setRecoveryOwnerUnavailable(d.isRecoveryOwnerUnavailable());
        e.setRecoveryTeam(d.getRecoveryTeam());
        e.setRecoveredAt(d.getRecoveredAt());
        e.setSatisfiedBy(d.getSatisfiedBy());
        e.setSatisfiedAt(d.getSatisfiedAt());
        e.setCreatedAt(d.getCreatedAt());
        e.setActivatedAt(d.getActivatedAt());
        e.setReviewDueAt(d.getReviewDueAt());
        e.setNextEscalationAt(d.getNextEscalationAt());
        e.setExpiresAt(d.getExpiresAt());
        e.setEscalationLevel(d.getEscalationLevel());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static StepInstance fromEntity(StepInstanceEntity e) {
        if (e == null) {
            return null;
        }
        StepInstance d = new StepInstance();
        d.setId(e.getId());
        d.setRequest(RequestMappers.fromEntity(e.getRequest()));
        d.setStep(WorkflowStepMappers.fromEntity(e.getStep()));
        d.setPosition(e.getPosition());
        d.setStepCode(e.getStepCode());
        d.setStepName(e.getStepName());
        d.setRequiredRole(e.getRequiredRole());
        d.setSlaMinutes(e.getSlaMinutes());
        d.setEscalationIntervalMinutes(e.getEscalationIntervalMinutes());
        d.setMaxEscalationLevels(e.getMaxEscalationLevels());
        d.setEscalationRole(e.getEscalationRole());
        d.setRosterSnapshotted(e.isRosterSnapshotted());
        d.setRosterSize(e.getRosterSize());

        d.setState(e.getState());
        d.setRule(e.getRule());
        d.setRequiredApprovals(e.getRequiredApprovals());
        d.setCondition(e.getCondition());
        d.setSkipReason(e.getSkipReason());
        d.setBlockedReason(e.getBlockedReason());
        d.setBlockReasonCode(e.getBlockReasonCode());
        d.setBlockedAt(e.getBlockedAt());
        d.setBlockEpisode(e.getBlockEpisode());
        d.setRecoveryOwnerId(e.getRecoveryOwnerId());
        d.setRecoveryOwnerRevision(e.getRecoveryOwnerRevision());
        d.setRecoveryOwnerUnavailable(e.isRecoveryOwnerUnavailable());
        d.setRecoveryTeam(e.getRecoveryTeam());
        d.setRecoveredAt(e.getRecoveredAt());
        d.setSatisfiedBy(e.getSatisfiedBy());
        d.setSatisfiedAt(e.getSatisfiedAt());
        d.setCreatedAt(e.getCreatedAt());
        d.setActivatedAt(e.getActivatedAt());
        d.setReviewDueAt(e.getReviewDueAt());
        d.setNextEscalationAt(e.getNextEscalationAt());
        d.setExpiresAt(e.getExpiresAt());
        d.setEscalationLevel(e.getEscalationLevel());
        return d;
    }
}
