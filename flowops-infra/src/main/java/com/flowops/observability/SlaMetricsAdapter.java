package com.flowops.observability;

import com.flowops.application.port.SlaMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Records the SLA breach / expiry counters for the SLA scanner
 * (PLAN §8). The scanner itself ({@code infra.worker}) and the
 * {@code ScanSla} use case stay telemetry-free.
 */
@Component
public class SlaMetricsAdapter implements SlaMetrics {

    private final MeterRegistry metrics;

    public SlaMetricsAdapter(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    public void recordBreach(String stepCode) {
        metrics.counter(MetricNames.SLA_BREACH, "step", stepCode).increment();
    }

    @Override
    public void recordExpiry(String stepCode) {
        metrics.counter(MetricNames.SLA_EXPIRED, "step", stepCode).increment();
    }
}
