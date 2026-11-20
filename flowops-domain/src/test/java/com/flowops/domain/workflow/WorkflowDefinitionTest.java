package com.flowops.domain.workflow;

import com.flowops.domain.exception.RequestValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowDefinitionTest {

    private final Workflow workflow = new Workflow("APPROVAL", "Approval", null);

    @Test
    void rejectsNonpositiveAndDuplicateExplicitPositions() {
        assertThrows(RequestValidationException.class, () -> workflow.defineSteps(List.of(spec(0, "ONE"))));
        assertThrows(RequestValidationException.class,
                () -> workflow.defineSteps(List.of(spec(2, "ONE"), spec(2, "TWO"))));
    }

    @Test
    void workflowStepOwnsItsConditionAndRejectsInvalidQuorums() {
        var step = new WorkflowStep(workflow, 1, "REVIEW", "Review", "ADMIN");
        assertThrows(IllegalArgumentException.class,
                () -> step.configure(CompletionRule.ANY_OF, 2, null, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> step.configure(CompletionRule.K_OF_N, 0, null, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> step.configure(CompletionRule.ALL_OF, 2, null, null, null, false));

        Map<String, Object> threshold = new LinkedHashMap<>(Map.of("amount", 10));
        step.configure(CompletionRule.ANY_OF, 1, Map.of("test", threshold), null, null, false);
        threshold.put("amount", 100);
        assertEquals(Map.of("test", Map.of("amount", 10)), step.getCondition());
        assertThrows(UnsupportedOperationException.class, step.getCondition()::clear);
    }

    private static StepSpec spec(int position, String code) {
        return new StepSpec(position, code, "Review", "ADMIN", null, null, null, null, null, null);
    }

}
