package com.flowops.worker;

import com.flowops.application.request.recovery.ReconcileEligibility;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EligibilityReconciler {

    private final ReconcileEligibility reconciliation;

    private final com.flowops.application.request.recovery.DrainIdentityReconciliation immediate;

    private volatile boolean running = true;

    public EligibilityReconciler(ReconcileEligibility reconciliation,
            com.flowops.application.request.recovery.DrainIdentityReconciliation immediate) {
        this.reconciliation = reconciliation;
        this.immediate = immediate;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    @Scheduled(fixedDelayString = "${flowops.recovery.identity-tick-delay-millis:1000}")
    public void scheduledIdentityTick() {
        if (running)
            immediate.drain();
    }

    @Scheduled(fixedDelayString = "${flowops.recovery.tick-delay-millis:30000}")
    public void scheduledTick() {
        if (running)
            reconciliation.scan();
    }

}
