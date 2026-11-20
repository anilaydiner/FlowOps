package com.flowops.application.execution;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.port.RequestRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Returns a handler command only after this proxied start transaction commits. */
@Service
public class StartExecutionJob {
    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final ExecutionJobRepository jobs;
    private final LifecycleAuditRecorder audit;
    private final Clock clock;
    private final ExecutionLeasePolicy timing;
    private final ExecutionAttemptRecorder attempts;
    private final com.flowops.application.execution.port.ExecutionRecoveryRepository recovery;
    private final ProviderContracts contracts;
    private final com.flowops.domain.execution.port.ExecutionPlanRepository plans;
    private final com.flowops.application.execution.port.ProviderContractRepository contractHistory;
    public StartExecutionJob(RequestRepository requests,RequestWriteLock requestWriteLock,ExecutionJobRepository jobs,LifecycleAuditRecorder audit,Clock clock,ProviderContracts contracts,com.flowops.application.execution.port.ProviderContractRepository contractHistory,
                              com.flowops.application.execution.port.ExecutionRecoveryRepository recovery,
                              ExecutionLeasePolicy timing,ExecutionAttemptRecorder attempts,com.flowops.domain.execution.port.ExecutionPlanRepository plans) {
        this.plans=plans;this.requests=requests;
        this.requestWriteLock = requestWriteLock;
        this.jobs=jobs;this.audit=audit;this.clock=clock;this.timing=timing;this.attempts=attempts;this.contracts=contracts;this.contractHistory=contractHistory;this.recovery=recovery;
    }
    public record Claim(ExecutionJob job,ActionCommand command,UUID token,Instant deadline,ProviderContract providerContract) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<Claim> start(ExecutionJobRepository.Candidate candidate) {
        var request=requestWriteLock.tryFindByIdForUpdate(candidate.requestId()).orElse(null);
        if(request==null || request.getStatus()!=RequestStatus.APPROVED || request.hasUnresolvedExecutionHistory()
                || request.getExecutionPlannedAt()==null || request.getExecutionPlanId()==null) return Optional.empty();
        var job=jobs.findForLockedRequest(candidate.jobId(),candidate.requestId()).orElse(null);
        if(job==null) return Optional.empty();
        if(!plans.jobMatches(request.getExecutionPlanId(),job.getId()))throw new com.flowops.domain.exception.ConflictException("execution-plan-mismatch","Job differs from its recorded manifest");
        var now=clock.instant();
        if(job.getStatus()==JobStatus.STARTED && job.getLeaseExpiresAt()!=null && !now.isBefore(job.getLeaseExpiresAt())) {
            job.requireReconciliation("Execution lease expired; external outcome is unknown");jobs.save(job);
            attempts.held(job,JobStatus.STARTED,"Execution lease expired");
            request.recordActivity();requests.save(request);
            audit.record(request,null,"JOB","RECONCILIATION_REQUIRED","job:"+job.getId()+":attempt:"+job.getAttemptCount()+":lease-expired",
                    "Expired execution claim requires reconciliation",Map.of("status","STARTED"),Map.of("status","RECONCILIATION_REQUIRED"),
                    Map.of("jobId",job.getId(),"attempt",job.getAttemptCount()),"Lease expiry does not prove that the external action stopped");
            return Optional.empty();
        }
        if(job.getStatus()==JobStatus.FAILED && (!contracts.hasRecordedProvider(job) || (!contracts.retrySafe(job)
                && !recovery.hasProviderRetryAuthorization(job.getId(),job.getAttemptCount())))) {
            job.requireReconciliation("Provider retry safety or historical provider binding is unavailable");jobs.save(job);
            attempts.held(job,JobStatus.FAILED,"Provider retry guarantee or historical binding unavailable");
            request.recordActivity();requests.save(request);
            audit.record(request,null,"JOB","RECONCILIATION_REQUIRED","job:"+job.getId()+":attempt:"+job.getAttemptCount()+":retry-guarantee-lost",
                    "Retry requires provider reconciliation",Map.of("status","FAILED"),Map.of("status","RECONCILIATION_REQUIRED"),
                    Map.of("jobId",job.getId(),"attempt",job.getAttemptCount()),"Current adapter cannot safely replay this logical action");
            return Optional.empty();
        }
        if(!job.isDue(now) || job.getInputPayload()==null) return Optional.empty();
        var providerContract=contracts.require(job);
        String prior=job.getStatus().name();
        request.markExecutionStarted(now);request.recordActivity();requests.save(request);
        UUID token=UUID.randomUUID();job.markStarted(now,token,now.plus(timing.lease()));jobs.save(job);
        attempts.started(job,now,now.plus(timing.maximumAttempt()));
        contractHistory.recordAttempt(token,job.getAction(),providerContract);
        audit.record(request,null,"JOB","STARTED","job:"+job.getId()+":attempt:"+job.getAttemptCount()+":started",
                "Execution start authorized",Map.of("status",prior),Map.of("status","STARTED"),
                Map.of("jobId",job.getId(),"action",job.getAction(),"attempt",job.getAttemptCount(),
                        "startedAt",job.getStartedAt().toString(),"leaseExpiresAt",job.getLeaseExpiresAt().toString()),null);
        return Optional.of(new Claim(job,new ActionCommand(job.getIdempotencyKey(),request.getId(),job.getAction(),job.getInputPayload(),job.getCorrelationId()),token,now.plus(timing.maximumAttempt()),providerContract));
    }
}
