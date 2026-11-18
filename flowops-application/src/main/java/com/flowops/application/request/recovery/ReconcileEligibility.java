package com.flowops.application.request.recovery;

import com.flowops.application.request.sla.ReconcileReviewRequest;
import com.flowops.domain.request.port.StepInstanceRepository;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded keyset sweep. Busy and failed requests are revisited on the next sweep. */
@Service
public class ReconcileEligibility {
    private static final Logger log=LoggerFactory.getLogger(ReconcileEligibility.class);
    private final StepInstanceRepository steps;
    private final ReconcileReviewRequest processor;
    private final int batchSize;
    private long cursor;
    private long sweepEnd;
    public ReconcileEligibility(StepInstanceRepository steps, ReconcileReviewRequest processor,
                                 @Value("${flowops.recovery.batch-size:50}") int batchSize) {
        if(batchSize<1 || batchSize>500) throw new IllegalArgumentException("Recovery batch size must be 1–500");
        this.steps=steps; this.processor=processor; this.batchSize=batchSize;
    }
    public record Result(int discovered,int processed,int failures) {}
    public synchronized Result scan() {
        if(sweepEnd==0) sweepEnd=steps.reviewWorkHighWatermark();
        long boundary=sweepEnd; // New arrivals cannot indefinitely postpone revisiting busy older work.
        var ids=steps.findReviewWork(cursor,batchSize,null).stream().filter(id->id<=boundary).toList();
        if(ids.isEmpty()) { cursor=0; sweepEnd=0; }
        int processed=0,failures=0;
        for(Long id:ids) {
            cursor=id;
            try { if(processor.process(id,true).processed()) processed++; }
            catch(RuntimeException failure) { failures++; log.error("Eligibility reconciliation failed for request {}",id,failure); }
        }
        if(cursor>=sweepEnd) { cursor=0; sweepEnd=0; }
        return new Result(ids.size(),processed,failures);
    }
}
