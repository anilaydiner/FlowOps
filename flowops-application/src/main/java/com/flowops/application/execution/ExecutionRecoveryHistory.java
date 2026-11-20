package com.flowops.application.execution;

import com.flowops.application.execution.model.ExecutionRecovery.*;
import com.flowops.application.execution.port.ExecutionRecoveryRepository;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.request.port.RequestRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionRecoveryHistory {

    public record Job(Long id, String action, String idempotencyKey, JobStatus status, int attemptCount, int retryCount,
            int maxRetries, Instant startedAt, boolean startInferred, Instant leaseExpiresAt, String lastError) {
        static Job from(ExecutionJob j) {
            return new Job(j.getId(), j.getAction(), j.getIdempotencyKey(), j.getStatus(), j.getAttemptCount(),
                    j.getRetryCount(), j.getMaxRetries(), j.getStartedAt(), j.isStartInferred(), j.getLeaseExpiresAt(),
                    j.getLastError());
        }
    }

    /** Deliberately excludes internal execution claim tokens. */
    public record Receipt(UUID commandId, Long jobId, Kind kind, Long actorId, String actorName, String reason,
            Long expectedVersion, int attemptCount, Instant createdAt, Instant expiresAt, String disposition,
            ProviderObservation provider, Instant recordedAt, String failureType) {
        public static Receipt from(Entry e) {
            var c = e.command();
            var o = e.observation();
            return new Receipt(c.id(), c.jobId(), c.kind(), c.actorId(), c.actorName(), c.reason(), c.expectedVersion(),
                    c.attemptCount(), c.createdAt(), c.expiresAt(), o == null ? "PENDING" : o.disposition().name(),
                    o == null ? null : o.provider(), o == null ? null : o.recordedAt(),
                    o == null ? null : o.failureType());
        }
    }

    public record View(Long requestId, Long requestVersion, List<Job> jobs, List<Receipt> commands) {
    }

    private final RequestRepository requests;

    private final ExecutionJobRepository jobs;

    private final ExecutionRecoveryRepository recovery;

    private final ExecutionRecoveryTransactions access;

    public ExecutionRecoveryHistory(RequestRepository requests, ExecutionJobRepository jobs,
            ExecutionRecoveryRepository recovery, ExecutionRecoveryTransactions access) {
        this.requests = requests;
        this.jobs = jobs;
        this.recovery = recovery;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public View get(Long requestId, int limit, CallerIdentity caller) {
        if (limit < 1 || limit > 100)
            throw new com.flowops.domain.exception.RequestValidationException(
                    Map.of("limit", "Use a limit from 1 to 100"));
        var r = requests.findById(requestId)
            .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
        access.require(r, caller, PermissionCodes.EXECUTION_READ);
        return new View(requestId, r.getVersion(),
                jobs.findByRequestIdOrderByCreatedAtAsc(requestId).stream().map(Job::from).toList(),
                recovery.history(requestId, limit).stream().map(Receipt::from).toList());
    }

}
