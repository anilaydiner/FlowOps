package com.flowops.worker;

import com.flowops.application.request.sla.ScanSla;
import com.flowops.observability.FlowOpsSpans;
import com.flowops.observability.SpanNames;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SLA scanner (PLAN §8, iteration 7).
 *
 * <p>Thin scheduling shell: the breach/expiry policy and escalation
 * notification live in the {@link ScanSla} use case, and the breach /
 * expiry counters in the {@code SlaMetrics} adapter. The worker only owns
 * the tick cadence, the test {@code running} flag, and the
 * {@code sla.scan} span wrapping each scan (with breach/expiry counts).
 *
 * <ul>
 *   <li><b>Breach</b> (now &gt; createdAt + slaMinutes): escalation
 *       notification via the outbox, idempotent per recipient.</li>
 *   <li><b>Expiry</b> (now &gt; createdAt + slaMinutes × 2): request
 *       transitions to the terminal EXPIRED state.</li>
 * </ul>
 */
@Component
public class SlaScanner {

    private final ScanSla scanSla;
    private final FlowOpsSpans spans;

    public SlaScanner(ScanSla scanSla,
                      FlowOpsSpans spans) {
        this.scanSla = scanSla;
        this.spans = spans;
    }

    private volatile boolean running = true;

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    @Scheduled(fixedDelay = WorkerDefaults.SLA_TICK_DELAY_MS)
    public void scheduledTick() {
        if (running) {
            scan();
        }
    }

    /**
     * Scan all PENDING step instances for SLA breach / expiry.
     * Called by the scheduler (when running) or directly by tests.
     */
    public void scan() {
        io.opentelemetry.api.trace.Span span =
                spans.start(SpanNames.SLA_SCAN, Map.of("component", "sla-scanner"));
        try {
            ScanSla.Result result = scanSla.scan();
            span.setAttribute("sla.breaches", result.breaches());
            span.setAttribute("sla.expiries", result.expiries());
            spans.success(span);
        } catch (RuntimeException e) {
            spans.error(span, e);
            throw e;
        }
    }
}
