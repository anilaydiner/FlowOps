package com.flowops.application.execution;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.execution.port.ExecutionJobObservability;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.request.port.RequestRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@Service
public class FinishExecutionJob {

    private final RequestRepository requests;

    private final RequestWriteLock requestWriteLock;

    private final ExecutionJobRepository jobs;

    private final LifecycleAuditRecorder audit;

    private final ExecutionJobObservability observability;

    private final Clock clock;

    private final CompleteRequestExecution completion;

    private final ExecutionAttemptRecorder attempts;

    public FinishExecutionJob(RequestRepository requests, RequestWriteLock requestWriteLock,
            ExecutionJobRepository jobs, LifecycleAuditRecorder audit, ExecutionJobObservability observability,
            Clock clock, CompleteRequestExecution completion, ExecutionAttemptRecorder attempts) {
        this.requests = requests;
        this.requestWriteLock = requestWriteLock;
        this.jobs = jobs;
        this.audit = audit;
        this.observability = observability;
        this.clock = clock;
        this.completion = completion;
        this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(StartExecutionJob.Claim claim, Map<String, Object> result, RuntimeException failure,
            boolean retrySafe) {
        var request = requestWriteLock.findByIdForUpdate(claim.command().requestId()).orElseThrow();
        var job = jobs.findForLockedRequest(claim.job().getId(), request.getId()).orElseThrow();
        if (job.getStatus() != JobStatus.STARTED || !claim.token().equals(job.getClaimToken()))
            return false;
        var now = clock.instant();
        boolean accepted = job.owns(claim.token(), now);
        if (!accepted) {
            job.requireReconciliation("A result arrived after the execution lease; reconciliation is required");
        }
        else if (failure == null) {
            job.markCompleted(result, now);
        }
        else if (failure instanceof ProviderCallFailure) {
            job.requireReconciliation("Provider timeout, capacity or invocation uncertainty requires reconciliation");
        }
        else if (retrySafe) {
            job.markFailed(failure.getMessage(), now.plus(job.retryBackoff()));
        }
        else {
            job.requireReconciliation(
                    "Non-idempotent action failed with an uncertain outcome: " + failure.getClass().getSimpleName());
        }
        jobs.save(job);
        attempts.outcome(job, failure, retrySafe);
        request.recordActivity();
        requests.save(request);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", job.getId());
        details.put("action", job.getAction());
        details.put("attempt", job.getAttemptCount());
        details.put("status", job.getStatus().name());
        details.put("retrySafe", retrySafe);
        if (failure != null)
            details.put("failureType", failure.getClass().getSimpleName());
        if (failure instanceof ProviderCallFailure providerFailure)
            details.put("providerFailureReason", providerFailure.reason().name());
        if (job.getNextRetryAt() != null)
            details.put("nextRetryAt", job.getNextRetryAt().toString());
        audit.record(request, null, "JOB", job.getStatus().name(),
                "job:" + job.getId() + ":attempt:" + job.getAttemptCount() + ":finished",
                "Execution job " + job.getAction() + " " + job.getStatus(), Map.of("status", "STARTED"),
                Map.of("status", job.getStatus().name(), "retryCount", job.getRetryCount()), details, null);
        if (job.getStatus() == JobStatus.COMPLETED)
            completion.completeLocked(request, null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (job.getStatus() == JobStatus.COMPLETED)
                    observability.recordCompleted(job);
                else
                    observability.recordFailed(job);
            }
        });
        return accepted;
    }

}
