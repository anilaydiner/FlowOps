package com.flowops.application.request.recovery;

import com.flowops.application.request.port.EligibilityReconciliationQueue;
import com.flowops.application.request.sla.ReconcileReviewRequest;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DrainIdentityReconciliation {
    private static final Logger log=LoggerFactory.getLogger(DrainIdentityReconciliation.class);
    private final EligibilityReconciliationQueue queue;
    private final ReconcileReviewRequest processor;
    private final int batchSize;
    public DrainIdentityReconciliation(EligibilityReconciliationQueue queue, ReconcileReviewRequest processor,
                                       @Value("${flowops.recovery.batch-size:50}") int batchSize) {
        if(batchSize<1 || batchSize>500) throw new IllegalArgumentException("Recovery batch size must be 1–500");
        this.queue=queue;this.processor=processor;this.batchSize=batchSize;
    }
    public void drain() {
        for(var ticket:queue.findReady(batchSize)) {
            try { if(!processor.processQueued(ticket)) queue.defer(ticket); }
            catch(RuntimeException failure) {
                log.error("Identity reconciliation failed for request {}",ticket.requestId(),failure);
                try { queue.defer(ticket); } catch(RuntimeException retryFailure) { log.error("Could not defer reconciliation hint",retryFailure); }
            }
        }
    }
}
