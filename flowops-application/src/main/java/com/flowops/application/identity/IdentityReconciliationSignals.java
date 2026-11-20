package com.flowops.application.identity;

import com.flowops.application.request.port.EligibilityReconciliationQueue;
import com.flowops.application.request.port.IdentityReconciliationWakeup;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/**
 * Queue inside identity transactions; never acquire workflow locks before identity
 * commit.
 */
@Service
public class IdentityReconciliationSignals {

    private static final Logger log = LoggerFactory.getLogger(IdentityReconciliationSignals.class);

    private final EligibilityReconciliationQueue queue;

    private final IdentityReconciliationWakeup wakeup;

    public IdentityReconciliationSignals(EligibilityReconciliationQueue queue, IdentityReconciliationWakeup wakeup) {
        this.queue = queue;
        this.wakeup = wakeup;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void userChanged(Long id) {
        queue.userChanged(id);
        afterCommit();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void roleChanged(Long id) {
        queue.roleChanged(id);
        afterCommit();
    }

    private void afterCommit() {
        if (TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(s -> s instanceof Wakeup)) {
            TransactionSynchronizationManager.registerSynchronization(new Wakeup());
        }
    }

    private class Wakeup implements TransactionSynchronization {

        @Override
        public void afterCommit() {
            try {
                wakeup.request();
            }
            catch (RuntimeException failure) {
                log.error("Immediate reconciliation unavailable; durable hints remain queued", failure);
            }
        }

    }

}
