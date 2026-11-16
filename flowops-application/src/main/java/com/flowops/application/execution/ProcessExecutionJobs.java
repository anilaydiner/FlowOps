package com.flowops.application.execution;

import com.flowops.application.execution.port.ExecutionJobObservability;
import com.flowops.domain.execution.ExecutionJob;
import com.flowops.domain.execution.JobStatus;
import com.flowops.domain.execution.port.ActionHandler;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.port.RequestRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: claim and execute a batch of due execution jobs
 * (PLAN §8, iteration 7).
 *
 * <p>Claims a batch ({@code FOR UPDATE SKIP LOCKED}, so multiple instances
 * can run concurrently without double-execution) and executes each job via
 * the {@link ActionHandler} port. Failures are recorded with exponential
 * backoff ({@link ExecutionJob#retryBackoff()}: 5s × 2^n) up to the
 * retry budget, after which the job is dead-lettered. When every job of a
 * request is COMPLETED the request transitions APPROVED → EXECUTED, guarded
 * by the {@code @Version} optimistic lock.</p>
 */
@Service
public class ProcessExecutionJobs {

    private static final Logger log = LoggerFactory.getLogger(ProcessExecutionJobs.class);

    private final ExecutionJobRepository jobs;
    private final RequestRepository requests;
    private final ActionHandler actionHandler;
    private final ExecutionJobObservability observability;

    public ProcessExecutionJobs(ExecutionJobRepository jobs,
                                RequestRepository requests,
                                ActionHandler actionHandler,
                                ExecutionJobObservability observability) {
        this.jobs = jobs;
        this.requests = requests;
        this.actionHandler = actionHandler;
        this.observability = observability;
    }

    @Transactional
    public void execute() {
        List<ExecutionJob> batch = jobs.claimBatch(Instant.now(), ExecutionJobRepository.DEFAULT_BATCH_SIZE);
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
        try {
            observability.runExecution(job, () -> {
                Request request = requests.findById(job.getRequestId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Request not found for job: " + job.getIdempotencyKey()));
                job.markStarted();

                Map<String, Object> result = actionHandler.execute(request, job.getAction());
                job.markCompleted(result);
                observability.recordCompleted(job);
                log.info("JOB COMPLETED: key={} action={}", job.getIdempotencyKey(), job.getAction());
                return null;
            });
        } catch (RuntimeException e) {
            Instant nextRetry = Instant.now().plus(job.retryBackoff());
            job.markFailed(e.getMessage(), nextRetry);
            observability.recordFailed(job);
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
