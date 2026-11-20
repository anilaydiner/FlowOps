package com.flowops.domain.request;

import com.flowops.domain.exception.InvalidStateTransitionException;
import com.flowops.domain.request.policy.ApprovalReachability;
import com.flowops.domain.workflow.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockedStepTest {

    private StepInstance step() {
        var definition = new WorkflowStep(new Workflow("B", "Blocked", null), 1, "REVIEW", "Review", "REVIEWER");
        definition.configure(CompletionRule.K_OF_N, 2, null, 10, null, false);
        var step = new StepInstance(null, definition);
        step.snapshotRoster(3);
        return step;
    }

    @Test
    void blockedIsActiveAndRecoveryPreservesPolicyAndDeadline() {
        var step = step();
        Instant created = step.getCreatedAt(), deadline = step.slaDeadline();
        Instant blocked = created.plusSeconds(30), recovered = blocked.plusSeconds(60);
        step.block(BlockReason.INSUFFICIENT_QUORUM, "Unavailable voters", 7L, "OPS", blocked);
        assertFalse(step.isTerminal());
        assertEquals(1, step.getBlockEpisode());
        assertThrows(InvalidStateTransitionException.class, () -> step.satisfy(1L));
        step.recover(recovered);
        assertEquals(StepState.PENDING, step.getState());
        assertEquals(created, step.getCreatedAt());
        assertEquals(deadline, step.slaDeadline());
        assertEquals(2, step.getRequiredApprovals());
        assertEquals(3, step.getRosterSize());
        assertEquals(blocked, step.getBlockedAt());
        assertEquals(recovered, step.getRecoveredAt());
        step.block(BlockReason.NO_ELIGIBLE_APPROVER, "Another episode", 7L, "OPS", recovered.plusSeconds(1));
        assertEquals(2, step.getBlockEpisode());
        assertNull(step.getRecoveredAt());
    }

    @Test
    void stoppedAndSatisfiedStepsCannotRecover() {
        var cancelled = step();
        cancelled.block(BlockReason.NO_ELIGIBLE_APPROVER, "None", 7L, "OPS", Instant.now());
        cancelled.cancel();
        assertTrue(cancelled.isTerminal());
        assertThrows(InvalidStateTransitionException.class, () -> cancelled.recover(Instant.now()));
        var rejected = step();
        rejected.reject();
        assertTrue(rejected.isTerminal());
        assertThrows(InvalidStateTransitionException.class, () -> rejected.recover(Instant.now()));
        var satisfied = step();
        satisfied.satisfy(1L);
        assertThrows(InvalidStateTransitionException.class, () -> satisfied.recover(Instant.now()));
    }

    @Test
    void quorumCountsHistoricalVotesPlusDistinctRemainingPeople() {
        assertNull(ApprovalReachability.blockReason(CompletionRule.K_OF_N, 2, 1, 1));
        assertEquals(BlockReason.INSUFFICIENT_QUORUM, ApprovalReachability.blockReason(CompletionRule.K_OF_N, 3, 1, 1));
        assertEquals(BlockReason.NO_ELIGIBLE_APPROVER,
                ApprovalReachability.blockReason(CompletionRule.ANY_OF, 1, 0, 0));
        assertEquals(BlockReason.PARTICIPANT_UNAVAILABLE,
                ApprovalReachability.blockReason(CompletionRule.ALL_OF, 3, 1, 1));
    }

    @Test
    void reviewSlaDoesNotDefineExpiryAndBusinessDeadlineUsesInclusiveBoundary() {
        var definition = new WorkflowStep(new Workflow("D", "Deadlines", null), 1, "REVIEW", "Review", "ADMIN");
        definition.configure(CompletionRule.ANY_OF, 1, null, 10, null, false);
        var start = Instant.parse("2026-01-01T00:00:00Z");
        var noExpiry = new StepInstance(null, definition, start);
        assertTrue(noExpiry.isSlaBreached(start.plusSeconds(600)));
        assertFalse(noExpiry.isExpired(start.plusSeconds(60000)));
        definition.setExpiryMinutes(30);
        var expiring = new StepInstance(null, definition, start);
        assertFalse(expiring.isExpired(start.plusSeconds(1799)));
        assertTrue(expiring.isExpired(start.plusSeconds(1800)));
        assertThrows(com.flowops.domain.exception.ConflictException.class,
                () -> expiring.requireUnexpired(start.plusSeconds(1800)));
        assertThrows(IllegalArgumentException.class, () -> definition.setExpiryMinutes(0));
    }

}
