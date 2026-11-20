package com.flowops.application.execution;

import com.flowops.application.request.RequestTargets;
import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.execution.model.ExecutionRecovery.*;
import com.flowops.application.execution.port.*;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.*;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.*;
import com.flowops.domain.identity.*;
import com.flowops.domain.identity.policy.*;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.RequestRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/**
 * All writes acquire parent then child; provider I/O belongs to the nontransactional
 * coordinator.
 */
@Service
public class ExecutionRecoveryTransactions {

    public record Input(UUID commandId, Long expectedVersion, String reason) {
    }

    public record Result(Long requestVersion, Entry entry) {
    }

    public record Prepared(Result result, ProviderQuery query, ProviderContract contract, Instant deadline) {
    }

    private final RequestRepository requests;

    private final RequestWriteLock requestWriteLock;

    private final ExecutionJobRepository jobs;

    private final ExecutionRecoveryRepository recovery;

    private final UserRepository users;

    private final AuthorizationEvaluator authz;

    private final LifecycleAuditRecorder audit;

    private final CompleteRequestExecution completion;

    private final ExecutionJobObservability metrics;

    private final ProviderContracts contracts;

    private final ProviderContractRepository contractHistory;

    private final Clock clock;

    private final Duration lease;

    private final ExecutionAttemptRecorder attempts;

    public ExecutionRecoveryTransactions(RequestRepository requests, RequestWriteLock requestWriteLock,
            ExecutionJobRepository jobs, ExecutionRecoveryRepository recovery, UserRepository users,
            AuthorizationEvaluator authz, LifecycleAuditRecorder audit, CompleteRequestExecution completion,
            ExecutionJobObservability metrics, ProviderContracts contracts, ProviderContractRepository contractHistory,
            Clock clock, ExecutionAttemptRecorder attempts,
            @Value("${flowops.execution.reconciliation-lease-seconds:60}") long seconds) {
        if (seconds < 1 || seconds > 86400)
            throw new IllegalArgumentException("Reconciliation lease must be 1–86400 seconds");
        this.requests = requests;
        this.requestWriteLock = requestWriteLock;
        this.jobs = jobs;
        this.recovery = recovery;
        this.users = users;
        this.authz = authz;
        this.audit = audit;
        this.completion = completion;
        this.metrics = metrics;
        this.contracts = contracts;
        this.contractHistory = contractHistory;
        this.clock = clock;
        this.attempts = attempts;
        lease = Duration.ofSeconds(seconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Prepared begin(Long requestId, Long jobId, Input input, CallerIdentity caller) {
        validate(input);
        var request = locked(requestId);
        require(request, caller, PermissionCodes.EXECUTION_RECONCILE);
        var existing = replay(request, jobId, input, Kind.RECONCILE, caller);
        if (existing != null)
            return new Prepared(existing, null, null, null);
        requireVersion(request, input);
        var job = job(requestId, jobId);
        requireRecoverable(request, job);
        var providerContract = contracts.require(job);
        requireNoPending(job);
        String prior = job.getStatus().name();
        if (job.getStatus() != JobStatus.RECONCILIATION_REQUIRED) {
            job.requireReconciliation("Provider reconciliation requested: " + input.reason());
            jobs.save(job);
            attempts.held(job, JobStatus.valueOf(prior), "Provider reconciliation requested");
        }
        var command = command(request, job, input, Kind.RECONCILE, caller);
        recovery.append(command);
        contractHistory.recordRecovery(command.id(), job.getAction(), providerContract);
        request.recordActivity();
        requests.save(request);
        fact(request, caller, command, "RECONCILIATION_STARTED", prior, job.getStatus().name(), Map.of());
        return new Prepared(new Result(request.getVersion(), recovery.find(input.commandId()).orElseThrow()),
                new ProviderQuery(job.getIdempotencyKey(), requestId, job.getAction()), providerContract,
                command.expiresAt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result finish(UUID commandId, ProviderObservation provider, String failureType) {
        var initial = recovery.find(commandId).orElseThrow();
        var request = locked(initial.command().requestId());
        var job = job(request.getId(), initial.command().jobId());
        var entry = recovery.find(commandId).orElseThrow();
        if (entry.observation() != null)
            return new Result(request.getVersion(), entry);
        var c = entry.command();
        var caller = new CallerIdentity(c.actorId(), c.actorName());
        String prior = job.getStatus().name();
        var latest = recovery.latest(job.getId()).orElseThrow();
        Disposition disposition;
        if (!allowed(request, caller, PermissionCodes.EXECUTION_RECONCILE))
            disposition = Disposition.AUTHORITY_REVOKED;
        else if (request.getStatus() != RequestStatus.APPROVED || !latest.command().id().equals(c.id())
                || !clock.instant().isBefore(c.expiresAt()) || job.getStatus() != JobStatus.RECONCILIATION_REQUIRED
                || !sameAttempt(c, job))
            disposition = Disposition.STALE;
        else if (failureType != null)
            disposition = Disposition.PROVIDER_ERROR;
        else if (provider.outcome() == ProviderObservation.Outcome.SUCCEEDED) {
            request.observeReconciledExecutionStart(clock.instant());
            requests.save(request);
            job.completeFromProvider(provider, clock.instant());
            jobs.save(job);
            disposition = Disposition.COMPLETED;
        }
        else
            disposition = Disposition.OBSERVED;
        recovery.append(new Observation(commandId, clock.instant(), disposition, provider, failureType));
        attempts.provider(job, c, provider, disposition.name());
        request.recordActivity();
        requests.save(request);
        fact(request, caller, c, "RECONCILIATION_" + disposition, prior, job.getStatus().name(),
                Map.of("providerOutcome", provider.outcome().name()));
        if (disposition == Disposition.COMPLETED) {
            completion.completeLocked(request, caller);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    metrics.recordCompleted(job);
                }
            });
        }
        return new Result(request.getVersion(), recovery.find(commandId).orElseThrow());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result retry(Long requestId, Long jobId, Input input, CallerIdentity caller) {
        validate(input);
        var request = locked(requestId);
        require(request, caller, PermissionCodes.EXECUTION_RETRY);
        var existing = replay(request, jobId, input, Kind.RETRY, caller);
        if (existing != null)
            return existing;
        requireVersion(request, input);
        var job = job(requestId, jobId);
        if (request.getStatus() != RequestStatus.APPROVED || job.getStatus() != JobStatus.RECONCILIATION_REQUIRED)
            throw conflict("execution-recovery-state", "Retry requires an approved request with held execution");
        if (request.hasUnresolvedExecutionHistory() || request.getExecutionPlannedAt() == null
                || request.getExecutionPlanId() == null || job.getInputPayload() == null)
            throw conflict("execution-state-unknown",
                    "Legacy work with missing plan, input or execution history cannot be replayed");
        requireNoPending(job);
        var evidence = recovery.latest(jobId)
            .orElseThrow(() -> conflict("reconciliation-required", "Reconcile the provider outcome before retry"));
        var o = evidence.observation();
        if (evidence.command().kind() != Kind.RECONCILE || !sameAttempt(evidence.command(), job) || o == null
                || o.disposition() != Disposition.OBSERVED)
            throw conflict("reconciliation-required",
                    "A current accepted provider observation is required before retry");
        var outcome = o.provider().outcome();
        if (outcome == ProviderObservation.Outcome.IN_PROGRESS || outcome == ProviderObservation.Outcome.SUCCEEDED)
            throw conflict("provider-action-in-progress", "Provider work cannot be replayed");
        var retryContract = contracts.require(job);
        if (!contracts.hasRecordedProvider(job))
            throw conflict("provider-binding-unknown",
                    "Historical provider identity needs explicit disposition before replay");
        if (outcome != ProviderObservation.Outcome.SAFE_TO_RETRY && !retryContract.idempotentRetries())
            throw conflict("execution-retry-unsafe",
                    "Provider evidence or adapter idempotency must guarantee a safe replay");
        boolean firstLegacy = job.getStartedAt() == null && job.getAttemptCount() == 0 && job.getRetryCount() == 0
                && request.getLegacyPlanDispositionId() != null;
        if (firstLegacy && outcome != ProviderObservation.Outcome.SAFE_TO_RETRY)
            throw conflict("execution-retry-unsafe",
                    "The first known legacy attempt requires provider fencing for the original key");
        var c = command(request, job, input, Kind.RETRY, caller);
        recovery.append(c);
        contractHistory.recordRecovery(c.id(), job.getAction(), retryContract);
        if (firstLegacy)
            job.authorizeLegacyFirstAttempt();
        else
            job.authorizeRecoveryRetry(clock.instant());
        jobs.save(job);
        recovery.append(new Observation(c.id(), clock.instant(), Disposition.RETRY_AUTHORIZED, o.provider(), null));
        attempts.retry(job, c,
                outcome == ProviderObservation.Outcome.SAFE_TO_RETRY ? "PROVIDER_FENCING" : "ADAPTER_IDEMPOTENCY");
        request.recordActivity();
        requests.save(request);
        fact(request, caller, c, "RETRY_AUTHORIZED", "RECONCILIATION_REQUIRED", job.getStatus().name(), Map.of(
                "providerOutcome", outcome.name(), "evidenceCommandId", evidence.command().id().toString(),
                "maxRetries", job.getMaxRetries(), "safetyBasis",
                outcome == ProviderObservation.Outcome.SAFE_TO_RETRY ? "PROVIDER_FENCING" : "ADAPTER_IDEMPOTENCY"));
        return new Result(request.getVersion(), recovery.find(c.id()).orElseThrow());
    }

    private boolean sameAttempt(Command c, ExecutionJob job) {
        return c.attemptCount() == job.getAttemptCount() && Objects.equals(c.claimToken(), job.getClaimToken());
    }

    private void requireRecoverable(Request request, ExecutionJob job) {
        if (request.getStatus() != RequestStatus.APPROVED)
            throw conflict("execution-recovery-state", "Recovery requires APPROVED");
        if (job.getStatus() == JobStatus.STARTED && job.getLeaseExpiresAt() != null
                && clock.instant().isBefore(job.getLeaseExpiresAt()))
            throw conflict("execution-attempt-active", "Execution still has an active lease");
        if (!Set.of(JobStatus.STARTED, JobStatus.RECONCILIATION_REQUIRED, JobStatus.FAILED, JobStatus.DEAD_LETTER)
            .contains(job.getStatus()))
            throw conflict("execution-recovery-state", "Job does not need execution recovery");
    }

    private void requireNoPending(ExecutionJob job) {
        recovery.latest(job.getId()).ifPresent(e -> {
            if (e.observation() == null && clock.instant().isBefore(e.command().expiresAt()))
                throw conflict("reconciliation-in-progress", "A provider reconciliation already holds this job");
        });
    }

    private Command command(Request request, ExecutionJob job, Input input, Kind kind, CallerIdentity caller) {
        var current = users.findById(caller.id()).orElseThrow();
        var now = clock.instant();
        return new Command(input.commandId(), null, request.getId(), job.getId(), kind, current.getId(),
                current.getUsername(), input.reason(), input.expectedVersion(), now, now.plus(lease),
                job.getAttemptCount(), job.getClaimToken());
    }

    private Result replay(Request request, Long jobId, Input input, Kind kind, CallerIdentity caller) {
        var existing = recovery.find(input.commandId()).orElse(null);
        if (existing == null)
            return null;
        var c = existing.command();
        if (!c.requestId().equals(request.getId()) || !c.jobId().equals(jobId) || c.kind() != kind
                || !c.actorId().equals(caller.id()) || !c.expectedVersion().equals(input.expectedVersion())
                || !c.reason().equals(input.reason()))
            throw conflict("recovery-command-conflict", "Command id was already used with different arguments");
        return new Result(request.getVersion(), existing);
    }

    private Request locked(Long requestId) {
        return requestWriteLock.findByIdForUpdate(requestId)
            .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
    }

    private ExecutionJob job(Long requestId, Long jobId) {
        return jobs.findForLockedRequest(jobId, requestId)
            .orElseThrow(() -> new NotFoundException("Execution job not found: " + jobId));
    }

    private void requireVersion(Request r, Input i) {
        if (!i.expectedVersion().equals(r.getVersion()))
            throw conflict("stale-execution-recovery", "Reload the request before recovering execution");
    }

    private void validate(Input i) {
        if (i == null || i.commandId() == null || i.expectedVersion() == null || i.expectedVersion() < 0
                || i.reason() == null || i.reason().isBlank() || i.reason().length() > 1000)
            throw new RequestValidationException(
                    Map.of("recovery", "commandId, expectedVersion and a reason of 1–1000 characters are required"));
    }

    public void require(Request r, CallerIdentity caller, String permission) {
        if (!allowed(r, caller, permission))
            throw new ForbiddenException("Missing active scoped execution permission: " + permission);
    }

    private boolean allowed(Request r, CallerIdentity caller, String permission) {
        var current = users.findById(caller.id()).orElse(null);
        return current != null && current.isActive()
                && authz.evaluate(current, permission, RequestTargets.from(r, null), true).allowed();
    }

    private void fact(Request r, CallerIdentity caller, Command c, String action, String before, String after,
            Map<String, Object> extras) {
        var details = new LinkedHashMap<String, Object>(extras);
        details.put("jobId", c.jobId());
        details.put("commandId", c.id().toString());
        details.put("attempt", c.attemptCount());
        audit.record(r, caller, "JOB", action, "execution-recovery:" + c.id() + ":" + action,
                "Execution recovery " + action, Map.of("status", before), Map.of("status", after), details, c.reason());
    }

    private ConflictException conflict(String code, String message) {
        return new ConflictException(code, message);
    }

}
