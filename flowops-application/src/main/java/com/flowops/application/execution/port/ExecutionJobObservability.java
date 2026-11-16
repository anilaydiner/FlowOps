package com.flowops.application.execution.port;

import com.flowops.domain.execution.ExecutionJob;
import java.util.function.Supplier;

/**
 * Driven-side port for execution-job observability (PLAN §8).
 *
 * <p>Wraps each job execution in a trace span and records the
 * completed / failed / dead-letter metrics. Keeps the use case free of
 * OpenTelemetry and Micrometer types.
 */
public interface ExecutionJobObservability {

    /** Runs {@code body} inside a {@code job.execute} trace span. */
    <T> T runExecution(ExecutionJob job, Supplier<T> body);

    /** Record the job-completion metric. */
    void recordCompleted(ExecutionJob job);

    /** Record the job-failure metric (plus dead-letter when retries are exhausted). */
    void recordFailed(ExecutionJob job);
}
