package com.flowops.worker;

import com.flowops.application.execution.ReconcileExecutionPlans;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CompletionReconciler {

    private final ReconcileExecutionPlans reconciliation;

    private volatile boolean running;

    public CompletionReconciler(ReconcileExecutionPlans reconciliation,
            @Value("${flowops.execution.completion-reconciliation-enabled:${flowops.execution.worker-enabled:true}}") boolean enabled,
            @Value("${flowops.execution.completion-tick-delay-millis:30000}") long delay) {
        if (delay < 100 || delay > 86400000)
            throw new IllegalArgumentException("Completion tick delay must be 100–86400000 milliseconds");
        this.reconciliation = reconciliation;
        this.running = enabled;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    @Scheduled(fixedDelayString = "${flowops.execution.completion-tick-delay-millis:30000}")
    public void scheduledTick() {
        if (running)
            reconciliation.scan();
    }

    public ReconcileExecutionPlans.Result process() {
        return reconciliation.scan();
    }

}
