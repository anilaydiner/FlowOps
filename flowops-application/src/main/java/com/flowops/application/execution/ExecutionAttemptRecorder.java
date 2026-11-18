package com.flowops.application.execution;

import com.flowops.application.execution.model.ExecutionAttempt;
import com.flowops.application.execution.model.ExecutionAttempt.*;
import com.flowops.application.execution.model.ExecutionRecovery.Command;
import com.flowops.application.execution.port.ExecutionAttemptRepository;
import com.flowops.domain.execution.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Immutable facts join the claim/outcome transaction. Failure to store evidence fails the mutation. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class ExecutionAttemptRecorder {
    private final ExecutionAttemptRepository repository;
    private final Clock clock;
    public ExecutionAttemptRecorder(ExecutionAttemptRepository repository,Clock clock) { this.repository=repository;this.clock=clock; }
    public void started(ExecutionJob job,Instant authorizedAt,Instant deadline) {
        repository.append(new ExecutionAttempt(job.getClaimToken(),job.getRequestId(),job.getId(),job.getAttemptCount(),
                job.getIdempotencyKey(),job.getAction(),job.getInputPayload(),job.getCorrelationId(),authorizedAt,job.getLeaseExpiresAt(),deadline));
    }
    public void heartbeat(ExecutionJob job) {
        event(job,job.getClaimToken(),Kind.HEARTBEAT,null,job.getLeaseExpiresAt(),Map.of(),null);
    }
    public void outcome(ExecutionJob job,RuntimeException failure,boolean retrySafe) {
        var details=new LinkedHashMap<String,Object>();
        details.put("retryCount",job.getRetryCount());details.put("retrySafe",retrySafe);
        if(failure!=null) details.put("failureType",failure.getClass().getSimpleName());
        if(failure instanceof ProviderCallFailure providerFailure) details.put("providerFailureReason",providerFailure.reason().name());
        if(job.getStatus()==JobStatus.COMPLETED && job.getResult()!=null) details.put("result",job.getResult());
        if(job.getNextRetryAt()!=null) details.put("nextRetryAt",job.getNextRetryAt().toString());
        event(job,job.getClaimToken(),Kind.OUTCOME,job.getStatus().name(),null,details,null);
    }
    public void held(ExecutionJob job,JobStatus prior,String reason) {
        event(job,job.getClaimToken(),prior==JobStatus.STARTED?Kind.OUTCOME:Kind.HOLD,job.getStatus().name(),null,Map.of("reason",reason),null);
    }
    public void provider(ExecutionJob job,Command c,ProviderObservation observation,String disposition) {
        event(job,c.claimToken(),Kind.PROVIDER_OBSERVATION,disposition,null,
                Map.of("providerOutcome",observation.outcome().name(),"observedAttemptCount",c.attemptCount()),c.id());
    }
    public void retry(ExecutionJob job,Command c,String safetyBasis) {
        event(job,c.claimToken(),Kind.RETRY_AUTHORIZED,"RETRY_AUTHORIZED",null,
                Map.of("maxRetries",job.getMaxRetries(),"safetyBasis",safetyBasis),c.id());
    }
    private void event(ExecutionJob job,UUID token,Kind kind,String outcome,Instant lease,Map<String,Object> details,UUID commandId) {
        var attempt=repository.find(token).orElse(null);
        if(attempt!=null && (!attempt.jobId().equals(job.getId()) || !attempt.requestId().equals(job.getRequestId())))
            throw new IllegalStateException("Attempt identity does not match job");
        if(attempt==null) repository.observeBaseline(new Baseline(job.getId(),job.getRequestId(),clock.instant(),job.getStatus().name(),
                job.getAttemptCount(),job.getStartedAt(),job.isStartInferred(),job.getClaimToken(),job.getLeaseExpiresAt()));
        repository.append(new Event(UUID.randomUUID(),null,job.getRequestId(),job.getId(),attempt==null?null:token,attempt==null?null:attempt.number(),attempt==null?job.getId():null,
                kind,clock.instant(),lease,outcome,details,commandId));
    }
}
