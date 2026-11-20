package com.flowops.observability;

import com.flowops.application.execution.port.ExecutionJobObservability;
import com.flowops.domain.execution.ExecutionJob;
import com.flowops.domain.execution.JobStatus;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Execution-job observability (PLAN §8): one {@code job.execute} span per job, completed
 * / failed / dead-letter counters, and the pending-jobs gauge (moved here from
 * {@code JobWorker} so the worker stays a thin scheduling shell).
 */
@Component
public class ExecutionJobObservabilityAdapter implements ExecutionJobObservability {

    private final FlowOpsSpans spans;

    private final MeterRegistry metrics;

    public ExecutionJobObservabilityAdapter(FlowOpsSpans spans, MeterRegistry metrics, ExecutionJobRepository jobs) {
        this.spans = spans;
        this.metrics = metrics;
        Gauge
            .builder(MetricNames.JOB_PENDING, jobs,
                    r -> r.countByStatus(JobStatus.PLANNED) + r.countByStatus(JobStatus.FAILED))
            .description("Execution jobs awaiting processing (PLANNED + FAILED)")
            .register(metrics);
    }

    @Override
    public <T> T runExecution(ExecutionJob job, Supplier<T> body) {
        return spans.run(SpanNames.JOB_EXECUTE, Map.of("job.key", job.getIdempotencyKey(), "job.action",
                job.getAction(), "request.id", String.valueOf(job.getRequestId())), body);
    }

    @Override
    public void recordCompleted(ExecutionJob job) {
        metrics.counter(MetricNames.JOB_COMPLETED, "action", job.getAction()).increment();
    }

    @Override
    public void recordFailed(ExecutionJob job) {
        metrics.counter(MetricNames.JOB_FAILED, "action", job.getAction()).increment();
        if (job.getStatus() == JobStatus.DEAD_LETTER) {
            metrics.counter(MetricNames.JOB_DEAD_LETTER, "action", job.getAction()).increment();
        }
    }

}
