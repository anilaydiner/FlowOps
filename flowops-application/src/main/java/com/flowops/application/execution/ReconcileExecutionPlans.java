package com.flowops.application.execution;

import com.flowops.application.execution.port.ExecutionPlanReconciliationRepository;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Bounded fair sweeps. Discovery never holds locks and one request's failure cannot roll
 * back another.
 */
@Service
public class ReconcileExecutionPlans {

    private static final Logger log = LoggerFactory.getLogger(ReconcileExecutionPlans.class);

    private final ExecutionPlanReconciliationRepository queue;

    private final ReconcileExecutionPlan processor;

    private final int batchSize;

    private long cursor, sweepEnd;

    public ReconcileExecutionPlans(ExecutionPlanReconciliationRepository queue, ReconcileExecutionPlan processor,
            @Value("${flowops.execution.completion-batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 500)
            throw new IllegalArgumentException("Completion reconciliation batch size must be 1–500");
        this.queue = queue;
        this.processor = processor;
        this.batchSize = batchSize;
    }

    public record Result(int discovered, int processed, int created, int restored, int rebound, int completed,
            int attention, int busy, int failures) {
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized Result scan() {
        if (sweepEnd == 0)
            sweepEnd = queue.highWatermark();
        var ids = queue.candidates(cursor, sweepEnd, batchSize);
        int processed = 0, created = 0, restored = 0, rebound = 0, completed = 0, attention = 0, busy = 0, failures = 0;
        for (Long id : ids) {
            cursor = id;
            try {
                var r = processor.process(id);
                if (r.outcome() == ReconcileExecutionPlan.Outcome.BUSY)
                    busy++;
                else if (r.outcome() != ReconcileExecutionPlan.Outcome.SKIPPED)
                    processed++;
                if (r.outcome() == ReconcileExecutionPlan.Outcome.COMPLETED)
                    completed++;
                if (r.outcome() == ReconcileExecutionPlan.Outcome.ATTENTION)
                    attention++;
                created += r.created();
                restored += r.restored();
                rebound += r.rebound();
            }
            catch (RuntimeException failure) {
                failures++;
                log.error("Execution plan reconciliation failed for request {}", id, failure);
            }
        }
        if (ids.isEmpty() || cursor >= sweepEnd) {
            cursor = 0;
            sweepEnd = 0;
        }
        return new Result(ids.size(), processed, created, restored, rebound, completed, attention, busy, failures);
    }

}
