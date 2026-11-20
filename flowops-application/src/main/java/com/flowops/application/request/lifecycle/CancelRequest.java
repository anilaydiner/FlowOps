package com.flowops.application.request.lifecycle;

import com.flowops.application.request.RequestTargets;
import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.port.RequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: cancel a request (any non-terminal state → CANCELLED).
 *
 * <p>
 * Requires {@code request:cancel:own} for the requester or {@code request:cancel} for
 * others.
 * </p>
 */
@Service
public class CancelRequest {

    private final LifecycleAuditRecorder audit;

    private final RequestRepository requests;

    private final RequestWriteLock requestWriteLock;

    private final com.flowops.domain.request.port.StepInstanceRepository steps;

    private final AuthorizationEvaluator authz;

    private final com.flowops.domain.execution.port.ExecutionJobRepository jobs;

    private final com.flowops.application.notification.outbox.OutboxService outbox;

    private final java.time.Clock clock;

    public CancelRequest(RequestRepository requests, RequestWriteLock requestWriteLock, AuthorizationEvaluator authz,
            LifecycleAuditRecorder audit, com.flowops.domain.request.port.StepInstanceRepository steps,
            com.flowops.domain.execution.port.ExecutionJobRepository jobs,
            com.flowops.application.notification.outbox.OutboxService outbox, java.time.Clock clock) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.clock = clock;
        this.steps = steps;
        this.audit = audit;
        this.requests = requests;
        this.requestWriteLock = requestWriteLock;
        this.authz = authz;
    }

    @Transactional
    public Request execute(Long id, CallerIdentity caller) {
        Request request = load(id);
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        TargetContext target = RequestTargets.from(request, null);
        authz.require(caller, isOwn ? PermissionCodes.REQUEST_CANCEL_OWN : PermissionCodes.REQUEST_CANCEL, target,
                isOwn);
        if (request.getStatus() == com.flowops.domain.request.RequestStatus.CANCELLED)
            return request;
        if (!request.getStatus().canCancel()) {
            throw new ConflictException("Cannot cancel a request in terminal state: " + request.getStatus());
        }
        var executionJobs = jobs.findAllForLockedRequest(id);
        var before = LifecycleAuditRecorder.state(request);
        request.cancel(clock.instant());
        if (executionJobs.stream()
            .anyMatch(job -> job.getStartedAt() != null || job.getAttemptCount() > 0 || job.getRetryCount() > 0
                    || (job.getStatus() != com.flowops.domain.execution.JobStatus.PLANNED
                            && job.getStatus() != com.flowops.domain.execution.JobStatus.CANCELLED)))
            throw new ConflictException("execution-already-started", "Execution history prevents cancellation");
        Request saved = requests.save(request);
        steps.findActiveForLockedRequest(id).ifPresent(step -> {
            String prior = step.getState().name();
            step.cancel();
            steps.save(step);
            audit.record(saved, caller, "STEP", "CANCELLED", "step:" + step.getId() + ":cancelled",
                    "Active step cancelled", java.util.Map.of("state", prior), java.util.Map.of("state", "CANCELLED"),
                    java.util.Map.of("stepInstanceId", step.getId()), "Request cancelled");
        });
        for (var job : executionJobs) {
            if (job.getStatus() == com.flowops.domain.execution.JobStatus.CANCELLED)
                continue;
            job.cancel(clock.instant(), caller.id());
            jobs.save(job);
            audit.record(saved, caller, "JOB", "CANCELLED", "job:" + job.getId() + ":cancelled",
                    "Unstarted execution job cancelled", java.util.Map.of("status", "PLANNED"),
                    java.util.Map.of("status", "CANCELLED"),
                    java.util.Map.of("jobId", job.getId(), "action", job.getAction()), "Request cancelled");
        }
        audit.transition(saved, caller, before, null);
        outbox.notifyFeed(id, saved.getRequester().getId(), "NOTIFY_CANCELLED", "Request cancelled",
                "Your request was cancelled before execution started", saved.getCorrelationId());
        outbox.notifyEmail(id, saved.getRequester().getId(), "CANCELLED", "Request cancelled",
                "Your request was cancelled before execution started", saved.getCorrelationId());
        return saved;
    }

    private Request load(Long id) {
        return requestWriteLock.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Request not found: " + id));
    }

}
