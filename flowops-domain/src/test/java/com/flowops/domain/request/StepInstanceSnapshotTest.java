package com.flowops.domain.request;

import com.flowops.domain.workflow.CompletionRule;
import com.flowops.domain.workflow.Workflow;
import com.flowops.domain.workflow.WorkflowStep;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StepInstanceSnapshotTest {

    private WorkflowStep definition(CompletionRule rule, int approvals) {
        WorkflowStep step = new WorkflowStep(new Workflow("WF_SNAPSHOT", "Snapshot", null), 1, "REVIEW", "Review",
                "REVIEWER");
        step.configure(rule, approvals, Map.of(), 10, "ESCALATOR", false);
        return step;
    }

    @Test
    void allOfUsesRosterSizeWhileNumericQuorumRemainsFixed() {
        StepInstance all = new StepInstance(null, definition(CompletionRule.ALL_OF, 1));
        all.snapshotRoster(3);
        assertEquals(3, all.getRequiredApprovals());
        assertEquals(3, all.getRosterSize());
        StepInstance quorum = new StepInstance(null, definition(CompletionRule.K_OF_N, 2));
        quorum.snapshotRoster(3);
        assertEquals(2, quorum.getRequiredApprovals());
    }

    @Test
    void rosterCannotBeReplacedOrFrozenAfterPersistence() {
        StepInstance step = new StepInstance(null, definition(CompletionRule.ALL_OF, 1));
        assertThrows(IllegalStateException.class, () -> step.snapshotRoster(-1));
        step.snapshotRoster(2);
        assertThrows(IllegalStateException.class, () -> step.snapshotRoster(3));
        StepInstance stored = new StepInstance(null, definition(CompletionRule.ANY_OF, 1));
        stored.setId(9L);
        assertThrows(IllegalStateException.class, () -> stored.snapshotRoster(1));
    }

    @Test
    void emptyAllOfStillNeedsApprovalAndCannotSucceedVacuously() {
        StepInstance step = new StepInstance(null, definition(CompletionRule.ALL_OF, 1));
        step.snapshotRoster(0);
        assertEquals(1, step.getRequiredApprovals());
        step.block(com.flowops.domain.request.BlockReason.NO_ELIGIBLE_APPROVER, "No participants", 1L, "OPERATIONS",
                java.time.Instant.now());
        assertEquals(StepState.BLOCKED, step.getState());
    }

    @Test
    void definitionEditsCannotChangeCapturedRoleConditionOrSla() {
        WorkflowStep definition = definition(CompletionRule.ALL_OF, 1);
        var nested = new LinkedHashMap<String, Object>();
        nested.put("amount", 100);
        definition.configure(CompletionRule.ALL_OF, 1, Map.of("nested", nested), 10, "ESCALATOR", false);
        StepInstance step = new StepInstance(null, definition, Instant.parse("2026-01-01T00:00:00Z"));
        definition.configure(CompletionRule.ANY_OF, 1, Map.of("different", true), 999, "OTHER", false);
        nested.put("amount", 900);
        assertEquals("REVIEWER", step.getRequiredRole());
        assertEquals("ESCALATOR", step.escalationRole());
        assertEquals(Instant.parse("2026-01-01T00:10:00Z"), step.slaDeadline());
        assertEquals(Map.of("nested", Map.of("amount", 100)), step.getCondition());
        assertThrows(UnsupportedOperationException.class, () -> step.getCondition().put("new", 1));
    }

}
