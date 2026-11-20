package com.flowops.domain.request;

import com.flowops.domain.exception.InvalidStateTransitionException;
import com.flowops.domain.workflow.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecurringEscalationTest {

    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");

    private StepInstance step(Integer sla, Integer expiry, Integer limit) {
        var definition = new WorkflowStep(new Workflow("ESC", "Escalation", null), 1, "REVIEW", "Review", "ADMIN");
        definition.configure(CompletionRule.ANY_OF, 1, null, sla, null, false);
        definition.setExpiryMinutes(expiry);
        definition.setEscalationIntervalMinutes(5);
        definition.setMaxEscalationLevels(limit);
        return new StepInstance(null, definition, start);
    }

    @Test
    void dueBoundariesAndLimitAreEnforced() {
        var step = step(10, null, 2);
        assertFalse(step.escalationDue(start.plusSeconds(599)));
        assertThrows(InvalidStateTransitionException.class, () -> step.recordEscalation(start.plusSeconds(599)));
        step.recordEscalation(start.plusSeconds(600));
        assertEquals(1, step.getEscalationLevel());
        assertEquals(start.plusSeconds(900), step.getNextEscalationAt());
        assertFalse(step.escalationDue(start.plusSeconds(899)));
        step.recordEscalation(start.plusSeconds(900));
        assertEquals(2, step.getEscalationLevel());
        assertNull(step.getNextEscalationAt());
        assertFalse(step.escalationDue(start.plusSeconds(60000)));
    }

    @Test
    void delayedScanDoesNotCauseACatchUpBurst() {
        var step = step(10, null, null);
        var delayed = start.plusSeconds(100000);
        step.recordEscalation(delayed);
        assertEquals(1, step.getEscalationLevel());
        assertEquals(delayed.plusSeconds(300), step.getNextEscalationAt());
        assertFalse(step.escalationDue(delayed));
    }

    @Test
    void blockedWorkWithoutSlaHasItsOwnReminderWindow() {
        var step = step(null, null, null);
        assertNull(step.getNextEscalationAt());
        step.block(BlockReason.NO_ELIGIBLE_APPROVER, "None", 1L, "OPS", start);
        assertEquals(start.plusSeconds(300), step.getNextEscalationAt());
        step.recordEscalation(start.plusSeconds(300));
        step.reassignRecoveryOwner(2L, "OPS");
        assertEquals(start.plusSeconds(600), step.getNextEscalationAt());
        step.recover(start.plusSeconds(301));
        assertNull(step.getNextEscalationAt());
        assertEquals(1, step.getEscalationLevel());
        step.block(BlockReason.NO_ELIGIBLE_APPROVER, "None", 2L, "OPS", start.plusSeconds(400));
        assertEquals(2, step.getBlockEpisode());
        assertEquals(0, step.getEscalationLevel());
        assertEquals(start.plusSeconds(700), step.getNextEscalationAt());
    }

    @Test
    void recoveryBeforeReviewDueDefersFurtherRemindersToTheOriginalDeadline() {
        var step = step(60, null, null);
        step.block(BlockReason.NO_ELIGIBLE_APPROVER, "None", 1L, "OPS", start);
        step.recordEscalation(start.plusSeconds(300));
        step.recover(start.plusSeconds(360));
        assertEquals(start.plusSeconds(3600), step.getNextEscalationAt());
        assertEquals(start.plusSeconds(3600), step.getReviewDueAt());
        assertEquals(1, step.getEscalationLevel());
    }

    @Test
    void expiryAndStoppedStepsNeverEscalate() {
        var expiring = step(1, 5, null);
        assertFalse(expiring.escalationDue(start.plusSeconds(300)));
        var cancelled = step(1, null, null);
        cancelled.cancel();
        assertFalse(cancelled.escalationDue(start.plusSeconds(60)));
        var satisfied = step(1, null, null);
        satisfied.satisfy(1L);
        assertFalse(satisfied.escalationDue(start.plusSeconds(60)));
    }

    @Test
    void noRecipientsBacksOffWithoutConsumingALevel() {
        var step = step(1, null, null);
        step.deferEscalation(start.plusSeconds(60));
        assertEquals(0, step.getEscalationLevel());
        assertEquals(start.plusSeconds(360), step.getNextEscalationAt());
    }

    @Test
    void workflowPolicyIsValidatedAndSnapshotted() {
        var definition = new WorkflowStep(new Workflow("ESC", "Escalation", null), 1, "REVIEW", "Review", "ADMIN");
        assertThrows(IllegalArgumentException.class, () -> definition.setEscalationIntervalMinutes(0));
        assertThrows(IllegalArgumentException.class, () -> definition.setMaxEscalationLevels(0));
        definition.setEscalationIntervalMinutes(5);
        definition.setMaxEscalationLevels(3);
        var step = new StepInstance(null, definition, start);
        definition.setEscalationIntervalMinutes(99);
        definition.setMaxEscalationLevels(1);
        assertEquals(5, step.getEscalationIntervalMinutes());
        assertEquals(3, step.getMaxEscalationLevels());
    }

}
