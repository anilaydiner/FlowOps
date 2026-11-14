package com.flowops.infra.worker;
import com.flowops.service.ActionHandler;

import com.flowops.domain.ExecutionJob;
import com.flowops.domain.ExecutionJobRepository;
import com.flowops.domain.JobStatus;
import com.flowops.domain.Request;
import com.flowops.domain.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.observability.FlowOpsSpans;
import com.flowops.observability.MetricNames;
import com.flowops.observability.SpanNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution job worker (PLAN §8, iteration 7).
 *
 * <p>Same poller pattern as {@code OutboxDispatcher}:
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED} for concurrent-safe batch claiming</li>
 *   <li>Exponential backoff retry (5s × 2^n), max 5 retries → DEAD_LETTER</li>
 *   <li>{@code running} flag for test determinism</li>
 * </ul>
 *
 * <p>After all jobs for a request complete, the request transitions
 * APPROVED → EXECUTED.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final ExecutionJobRepository jobs;
    private final RequestRepository requests;
    private final ActionHandler actionHandler;
    private final MeterRegistry metrics;
    private final FlowOpsSpans spans;

    public JobWorker(ExecutionJobRepository jobs,
                     RequestRepository requests,
                     ActionHandler actionHandler,
                     MeterRegistry metrics,
                     FlowOpsSpans spans) {
        this.jobs = jobs;
        this.requests = requests;
        this.actionHandler = actionHandler;
        this.metrics = metrics;
        this.spans = spans;
        Gauge.builder(MetricNames.JOB_PENDING, this,
                w -> w.jobs.countByStatus(JobStatus.PLANNED) + w.jobs.countByStatus(JobStatus.FAILED))
                .description("Execution jobs awaiting processing (PLANNED + FAILED)")
                .register(metrics);
    }

    private volatile boolean running = true;

    public void setRunning(boolean running) {
        this.running = running;
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
     * Claim and execute a batch of due jobs.
     * Called by the scheduler (when running) or directly by tests.
     */
    @Transactional
    public void process() {
        List<ExecutionJob> batch = jobs.claimBatch(Instant.now(), WorkerDefaults.BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        log.info("JOB WORKER: claiming {} jobs", batch.size());

        for (ExecutionJob job : batch) {
            execute(job);
        }
        jobs.saveAll(batch);

        // Check which requests have all jobs done → EXECUTED
        Set<Long> affectedRequestIds = new HashSet<>();
        for (ExecutionJob job : batch) {
            if (job.getStatus() == JobStatus.COMPLETED) {
                affectedRequestIds.add(job.getRequestId());
            }
        }
        for (Long reqId : affectedRequestIds) {
            transitionIfAllDone(reqId);
        }
    }

    private void execute(ExecutionJob job) {
        Map<String, String> attrs = Map.of(
                "job.key", job.getIdempotencyKey(),
                "job.action", job.getAction(),
                "request.id", String.valueOf(job.getRequestId()));
        try {
            spans.run(SpanNames.JOB_EXECUTE, attrs, () -> {
                Request request = requests.findById(job.getRequestId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Request not found for job: " + job.getIdempotencyKey()));
                job.markStarted();

                Map<String, Object> result = actionHandler.execute(request, job.getAction());
                job.markCompleted(result);
                metrics.counter(MetricNames.JOB_COMPLETED, "action", job.getAction()).increment();
                log.info("JOB COMPLETED: key={} action={}", job.getIdempotencyKey(), job.getAction());
                return null;
            });
        } catch (RuntimeException e) {
            long backoffSeconds = WorkerDefaults.BASE_BACKOFF.toSeconds() * (1L << job.getRetryCount());
            Instant nextRetry = Instant.now().plus(Duration.ofSeconds(backoffSeconds));
            job.markFailed(e.getMessage(), nextRetry);
            metrics.counter(MetricNames.JOB_FAILED, "action", job.getAction()).increment();
            if (job.getStatus() == JobStatus.DEAD_LETTER) {
                metrics.counter(MetricNames.JOB_DEAD_LETTER, "action", job.getAction()).increment();
            }
            log.warn("JOB FAIL (retry={}/{}): key={} action={} error={}",
                    job.getRetryCount(), job.getMaxRetries(),
                    job.getIdempotencyKey(), job.getAction(), e.getMessage());
        }
    }

    /**
     * Transition APPROVED → EXECUTED when all jobs for the request are COMPLETED.
     * Guarded by the {@code @Version} optimistic lock: if two workers race,
     * the second save throws OptimisticLockException which Spring swallows.
     */
    private void transitionIfAllDone(Long requestId) {
        long total = jobs.countByRequestId(requestId);
        if (total == 0) return;
        long pending = jobs.countByRequestIdAndStatusNot(requestId, JobStatus.COMPLETED);
        if (pending > 0) return;

        Request request = requests.findById(requestId).orElse(null);
        if (request == null) return;
        if (request.getStatus() != RequestStatus.APPROVED) {
            log.debug("EXEC SKIP transition (status={}): request={}", request.getStatus(), requestId);
            return;
        }

        request.transitionTo(RequestStatus.EXECUTED);
        requests.save(request);
        log.info("REQUEST EXECUTED: id={} (all {} jobs completed)", requestId, total);
    }
}
