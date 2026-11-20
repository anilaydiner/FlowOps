package com.flowops.domain.request;

import com.flowops.domain.execution.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionPlanTest {

    @Test
    void manifestFreezesNestedInputsAndItsActionList() {
        var nested = new ArrayList<>(List.of("approved"));
        var input = new LinkedHashMap<String, Object>();
        input.put("items", nested);
        input.put("optional", null);
        var actions = new ArrayList<ExecutionPlan.Action>();
        actions.add(new ExecutionPlan.Action(1, "ACTION", "key", input, null));
        var plan = new ExecutionPlan(UUID.randomUUID(), 1L, 2L, "TYPE", null, Instant.now(),
                ApprovalEvidence.allSkipped(3L, "requester"), actions);
        nested.clear();
        input.clear();
        actions.clear();
        assertEquals(1, plan.actions().size());
        assertEquals(List.of("approved"), plan.actions().getFirst().input().get("items"));
        assertTrue(plan.actions().getFirst().input().containsKey("optional"));
        assertThrows(UnsupportedOperationException.class, () -> plan.actions().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.actions().getFirst().input().clear());
    }

    @Test
    void automaticApprovalPreservesItsInitiatorWithoutInventingAHumanVote() {
        var evidence = ApprovalEvidence.allSkipped(3L, "requester");
        assertEquals(ApprovalEvidence.ActorKind.SYSTEM, evidence.actorKind());
        assertNull(evidence.actorId());
        assertNull(evidence.decisionId());
        assertEquals(3L, evidence.initiatedById());
        assertEquals(evidence, ApprovalEvidence.fromSnapshot(evidence.snapshot()));
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalEvidence(ApprovalEvidence.Trigger.FINAL_HUMAN_APPROVAL,
                        ApprovalEvidence.ActorKind.SYSTEM, null, "system", 3L, "requester", null, null));
    }

}
