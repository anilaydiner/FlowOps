package com.flowops.application.request.sla;

import com.flowops.domain.request.port.StepInstanceRepository;
import java.time.Clock;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Bounded discovery only: every mutation commits in its own parent-locked transaction.
 */
@Service
public class ScanSla {

    private static final Logger log = LoggerFactory.getLogger(ScanSla.class);

    private final StepInstanceRepository steps;

    private final ReconcileReviewRequest processor;

    private final Clock clock;

    private final int batchSize;

    private long cursor;

    private long sweepEnd;

    public ScanSla(StepInstanceRepository steps, ReconcileReviewRequest processor, Clock clock,
            @Value("${flowops.sla.batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 500)
            throw new IllegalArgumentException("SLA batch size must be 1–500");
        this.steps = steps;
        this.processor = processor;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public record Result(int breaches, int expiries) {
    }

    public synchronized Result scan() {
        if (sweepEnd == 0)
            sweepEnd = steps.reviewWorkHighWatermark();
        long boundary = sweepEnd; // New arrivals cannot indefinitely postpone revisiting
                                  // busy older work.
        var ids = steps.findReviewWork(cursor, batchSize, clock.instant())
            .stream()
            .filter(id -> id <= boundary)
            .toList();
        if (ids.isEmpty()) {
            cursor = 0;
            sweepEnd = 0;
        }
        int breaches = 0, expiries = 0;
        for (Long id : ids) {
            cursor = id;
            try {
                var result = processor.process(id, false);
                breaches += result.breaches();
                expiries += result.expiries();
            }
            catch (RuntimeException failure) {
                log.error("Review deadline processing failed for request {}", id, failure);
            }
        }
        if (cursor >= sweepEnd) {
            cursor = 0;
            sweepEnd = 0;
        }
        return new Result(breaches, expiries);
    }

}
