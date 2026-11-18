package com.flowops.worker;

import com.flowops.application.request.sla.ScanSla;
import com.flowops.observability.FlowOpsSpans;
import com.flowops.observability.SpanNames;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Thin scheduling shell for bounded pending/blocked review escalation and independent business expiry. */
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
     * Scan a bounded batch of due PENDING/BLOCKED review work.
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
