package com.flowops;

import com.flowops.application.request.RequestDetailReader;
import com.flowops.domain.Decision;
import com.flowops.domain.DecisionAction;
import com.flowops.domain.Request;
import com.flowops.domain.StepInstance;
import com.flowops.domain.port.ClarificationRepository;
import com.flowops.domain.port.DecisionRepository;
import com.flowops.domain.port.ExecutionJobRepository;
import com.flowops.domain.port.StepInstanceRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestDetailReaderTest {
    @Test
    void batchesAndGroupsInterleavedDecisionsWhileKeepingStepsWithoutDecisions() {
        var steps = mock(StepInstanceRepository.class);
        var decisions = mock(DecisionRepository.class);
        var reader = new RequestDetailReader(steps, decisions,
                mock(ClarificationRepository.class), mock(ExecutionJobRepository.class));
        Request request = Request.newDraft(null, null, null, null);
        request.recordPersistence(12L, 0L);
        StepInstance first = new StepInstance();
        first.setId(1L);
        StepInstance second = new StepInstance();
        second.setId(2L);
        StepInstance empty = new StepInstance();
        empty.setId(3L);
        Decision secondEarly = new Decision(request, second, 7L, "actor", DecisionAction.APPROVE, "early", Map.of());
        Decision firstOnly = new Decision(request, first, 7L, "actor", DecisionAction.APPROVE, "only", Map.of());
        Decision secondLate = new Decision(request, second, 8L, "other", DecisionAction.APPROVE, "late", Map.of());
        when(steps.findByRequestIdOrderById(12L)).thenReturn(List.of(first, second, empty));
        when(decisions.findByRequestIdOrderById(12L)).thenReturn(List.of(secondEarly, firstOnly, secondLate));

        var result = reader.load(request);

        assertEquals(List.of(first, second, empty), result.steps());
        assertEquals(List.of(firstOnly), result.decisionsByStep().get(1L));
        assertEquals(List.of(secondEarly, secondLate), result.decisionsByStep().get(2L));
        assertEquals(List.of(), result.decisionsByStep().get(3L));
        verify(decisions).findByRequestIdOrderById(12L);
        verify(decisions, never()).findByStepInstanceIdOrderById(anyLong());
    }
}
