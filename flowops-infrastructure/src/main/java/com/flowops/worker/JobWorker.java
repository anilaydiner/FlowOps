package com.flowops.worker;

import com.flowops.application.execution.ProcessExecutionJobs;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Execution job worker (PLAN §8, iteration 7).
 *
 * <p>
 * Thin scheduling shell: batch claiming, execution, exponential-backoff retry (5s × 2^n,
 * max 5 → DEAD_LETTER) and the APPROVED → EXECUTED transition all live in the
 * {@link ProcessExecutionJobs} use case; spans, counters, and the pending gauge live in
 * the {@code ExecutionJobObservability} adapter. The worker only owns the tick cadence
 * and the test {@code running} flag.
 */
@Component
public class JobWorker implements com.flowops.application.execution.port.ExecutionWorkerStatus {

    private final ProcessExecutionJobs process;

    private final CompletionReconciler completion;

    public JobWorker(ProcessExecutionJobs process, CompletionReconciler completion,
            @org.springframework.beans.factory.annotation.Value("${flowops.execution.worker-enabled:true}") boolean enabled) {
        this.process = process;
        this.completion = completion;
        this.running = enabled;
    }

    /**
     * When {@code false} (tests / maintenance), the background poller no-ops but
     * {@link #process()} can still be invoked manually.
     */
    private volatile boolean running = true;

    public void setRunning(boolean running) {
        this.running = running;
    }

    @Override
    public boolean isCompletionReconciliationRunning() {
        return completion.isRunning();
    }

    public boolean isRunning() {
        return running;
    }

    @Scheduled(fixedDelay = WorkerDefaults.TICK_DELAY_MS)
    public void scheduledTick() {
        if (running) {
            process();
        }
    }

    /**
     * Claim and execute a batch of due jobs via the use case. Called by the scheduler
     * (when running) or directly by tests.
     */
    public void process() {
        process.execute();
    }

}
