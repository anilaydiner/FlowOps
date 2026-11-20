package com.flowops.worker;

import com.flowops.application.notification.outbox.DeliverOutbox;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox dispatcher (PLAN §8): polls the outbox table and delivers messages.
 *
 * <p>
 * Thin scheduling shell: channel routing, retry with exponential backoff (5s × 2^n, max 5
 * → DEAD_LETTER), and the pending gauge all live in the {@link DeliverOutbox} use case
 * and the {@code OutboxObservability} adapter. The worker only owns the tick cadence and
 * the test {@code running} flag.
 *
 * <p>
 * <b>Concurrency-safe</b>: the claim uses {@code FOR UPDATE SKIP LOCKED} so multiple
 * instances can poll concurrently without double-delivering.
 */
@Component
public class OutboxDispatcher {

    private final DeliverOutbox dispatch;

    public OutboxDispatcher(DeliverOutbox dispatch) {
        this.dispatch = dispatch;
    }

    /**
     * When {@code false} (tests / maintenance), the background poller no-ops but
     * {@link #dispatch()} can still be invoked manually.
     */
    private volatile boolean running = true;

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Scheduled poller: runs every 2 seconds.
     */
    @Scheduled(fixedDelay = WorkerDefaults.TICK_DELAY_MS)
    public void scheduledTick() {
        if (running) {
            dispatch();
        }
    }

    /**
     * Deliver a batch of due outbox messages via the use case. Called by the scheduler
     * (when running) or directly by tests.
     */
    public void dispatch() {
        dispatch.dispatch();
    }

}
