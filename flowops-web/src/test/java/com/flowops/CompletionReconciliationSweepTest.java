package com.flowops;

import com.flowops.application.execution.*;
import com.flowops.application.execution.port.ExecutionPlanReconciliationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompletionReconciliationSweepTest {

    private ReconcileExecutionPlan.Result result(ReconcileExecutionPlan.Outcome outcome) {
        return new ReconcileExecutionPlan.Result(outcome, 0, 0, 0, List.of());
    }

    @Test
    void boundedHighWatermarkSweepsRevisitBusyAndFailedWorkBeforeNewArrivals() {
        var queue = mock(ExecutionPlanReconciliationRepository.class);
        var processor = mock(ReconcileExecutionPlan.class);
        when(queue.highWatermark()).thenReturn(4L, 6L);
        when(queue.candidates(0, 4, 2)).thenReturn(List.of(1L, 2L));
        when(queue.candidates(2, 4, 2)).thenReturn(List.of(3L, 4L));
        when(queue.candidates(0, 6, 2)).thenReturn(List.of(1L, 2L));
        when(processor.process(1L)).thenReturn(result(ReconcileExecutionPlan.Outcome.BUSY),
                result(ReconcileExecutionPlan.Outcome.UNCHANGED));
        when(processor.process(2L)).thenThrow(new IllegalStateException("injected failure"))
            .thenReturn(result(ReconcileExecutionPlan.Outcome.UNCHANGED));
        when(processor.process(3L)).thenReturn(result(ReconcileExecutionPlan.Outcome.COMPLETED));
        when(processor.process(4L)).thenReturn(result(ReconcileExecutionPlan.Outcome.UNCHANGED));
        var scan = new ReconcileExecutionPlans(queue, processor, 2);
        var first = scan.scan();
        assertEquals(2, first.discovered());
        assertEquals(1, first.busy());
        assertEquals(1, first.failures());
        assertEquals(1, scan.scan().completed());
        assertEquals(2, scan.scan().processed());
        verify(processor, times(2)).process(1L);
        verify(processor, times(2)).process(2L);
        verify(processor, never()).process(5L);
    }

    @Test
    void emptySweepsResetTheirBoundaryAndInvalidBatchSizesFailFast() {
        var queue = mock(ExecutionPlanReconciliationRepository.class);
        var processor = mock(ReconcileExecutionPlan.class);
        when(queue.highWatermark()).thenReturn(3L, 5L);
        when(queue.candidates(anyLong(), anyLong(), anyInt())).thenReturn(List.of());
        var scanner = new ReconcileExecutionPlans(queue, processor, 2);
        assertEquals(0, scanner.scan().discovered());
        assertEquals(0, scanner.scan().discovered());
        verify(queue).candidates(0, 3, 2);
        verify(queue).candidates(0, 5, 2);
        assertThrows(IllegalArgumentException.class, () -> new ReconcileExecutionPlans(queue, processor, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReconcileExecutionPlans(queue, processor, 501));
        verifyNoInteractions(processor);
    }

}
