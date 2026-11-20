package com.flowops.application.execution;

import com.flowops.application.execution.model.ExecutionAttempt;
import com.flowops.application.execution.port.ExecutionAttemptRepository;
import com.flowops.domain.exception.*;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.request.port.RequestRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped, bounded operator projections. Internal claim tokens and frozen input stay out
 * of HTTP.
 */
@Service
@Transactional(readOnly = true)
public class ExecutionAttemptHistory {

    public record Attempt(int number, String action, Instant authorizedAt, Instant initialLeaseExpiresAt,
            Instant deadline, String correlationId) {
        static Attempt from(ExecutionAttempt a) {
            return new Attempt(a.number(), a.action(), a.authorizedAt(), a.initialLeaseExpiresAt(), a.deadline(),
                    a.correlationId());
        }
    }

    public record Baseline(Instant observedAt, String status, int observedAttemptCount, Instant firstStartedAt,
            boolean startInferred, Instant observedLeaseExpiresAt) {
        static Baseline from(ExecutionAttempt.Baseline b) {
            return new Baseline(b.observedAt(), b.status(), b.attemptCount(), b.firstStartedAt(), b.startInferred(),
                    b.observedLeaseExpiresAt());
        }
    }

    public record Event(Long sequence, Integer attemptNumber, boolean baseline, ExecutionAttempt.Kind kind, Instant at,
            Instant leaseExpiresAt, String outcome, Map<String, Object> details, UUID recoveryCommandId) {
        static Event from(ExecutionAttempt.Event e) {
            return new Event(e.sequence(), e.attemptNumber(), e.baselineJobId() != null, e.kind(), e.at(),
                    e.leaseExpiresAt(), e.outcome(), e.details(), e.recoveryCommandId());
        }
    }

    public record Attempts(Long jobId, Baseline baseline, List<Attempt> attempts) {
    }

    private final RequestRepository requests;

    private final ExecutionJobRepository jobs;

    private final ExecutionAttemptRepository repository;

    private final ExecutionRecoveryTransactions access;

    public ExecutionAttemptHistory(RequestRepository requests, ExecutionJobRepository jobs,
            ExecutionAttemptRepository repository, ExecutionRecoveryTransactions access) {
        this.requests = requests;
        this.jobs = jobs;
        this.repository = repository;
        this.access = access;
    }

    public Attempts attempts(Long requestId, Long jobId, Integer before, int limit, CallerIdentity caller) {
        require(requestId, jobId, limit, caller);
        if (before != null && before < 1)
            throw invalid();
        return new Attempts(jobId, repository.baseline(jobId).map(Baseline::from).orElse(null),
                repository.attempts(requestId, jobId, before, limit).stream().map(Attempt::from).toList());
    }

    public List<Event> events(Long requestId, Long jobId, Long before, int limit, CallerIdentity caller) {
        require(requestId, jobId, limit, caller);
        if (before != null && before < 1)
            throw invalid();
        return repository.events(requestId, jobId, before, limit).stream().map(Event::from).toList();
    }

    private void require(Long requestId, Long jobId, int limit, CallerIdentity caller) {
        if (limit < 1 || limit > 100)
            throw invalid();
        var request = requests.findById(requestId)
            .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
        access.require(request, caller, PermissionCodes.EXECUTION_READ);
        jobs.findById(jobId)
            .filter(j -> requestId.equals(j.getRequestId()))
            .orElseThrow(() -> new NotFoundException("Execution job not found: " + jobId));
    }

    private RequestValidationException invalid() {
        return new RequestValidationException(
                Map.of("pagination", "Limit must be 1–100 and any cursor must be positive"));
    }

}
