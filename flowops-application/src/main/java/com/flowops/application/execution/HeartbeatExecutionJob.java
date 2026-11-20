package com.flowops.application.execution;

import com.flowops.application.execution.port.ExecutionAttemptRepository;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.execution.ExecutionLease;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.port.RequestRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class HeartbeatExecutionJob {
    public enum Renewal { RENEWED, UNCHANGED, BUSY, LOST }
    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final ExecutionJobRepository jobs;
    private final ExecutionAttemptRepository attempts;
    private final ExecutionAttemptRecorder recorder;
    private final ExecutionLeasePolicy timing;
    private final Clock clock;
    public HeartbeatExecutionJob(RequestRepository requests,RequestWriteLock requestWriteLock,ExecutionJobRepository jobs,ExecutionAttemptRepository attempts,
                                 ExecutionAttemptRecorder recorder,ExecutionLeasePolicy timing,Clock clock) {
        this.requests=requests;
        this.requestWriteLock = requestWriteLock;
        this.jobs=jobs;this.attempts=attempts;this.recorder=recorder;this.timing=timing;this.clock=clock;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Renewal renew(ExecutionLease claim) {
        var request=requestWriteLock.tryFindByIdForUpdate(claim.requestId()).orElse(null);
        if(request==null) return Renewal.BUSY;
        if(request.getStatus()!=RequestStatus.APPROVED || request.hasUnresolvedExecutionHistory() || request.getExecutionPlannedAt()==null || request.getExecutionPlanId()==null)
            return Renewal.LOST;
        var job=jobs.findForLockedRequest(claim.jobId(),request.getId()).orElse(null);
        var attempt=attempts.find(claim.token()).orElse(null);var now=clock.instant();
        if(job==null || attempt==null || !attempt.requestId().equals(request.getId()) || !attempt.jobId().equals(job.getId())
                || attempt.number()!=claim.attemptNumber() || job.getAttemptCount()!=claim.attemptNumber()
                || !job.owns(claim.token(),now) || now.isBefore(attempt.authorizedAt()) || !now.isBefore(attempt.deadline()))
            return Renewal.LOST;
        var expiry=now.plus(timing.lease());if(expiry.isAfter(attempt.deadline())) expiry=attempt.deadline();
        if(!job.renew(claim.token(),now,expiry)) return Renewal.UNCHANGED;
        jobs.save(job);recorder.heartbeat(job);request.recordActivity();requests.save(request);
        return Renewal.RENEWED;
    }
}
