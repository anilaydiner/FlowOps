package com.flowops.application.execution;

import com.flowops.application.execution.port.ExecutionJobObservability;
import com.flowops.domain.execution.port.ActionHandler;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import java.time.Clock;
import java.util.Map;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Discover, commit start, invoke outside transactions, then commit a fenced outcome. */
@Service
public class ProcessExecutionJobs {

    private static final Logger log = LoggerFactory.getLogger(ProcessExecutionJobs.class);

    private final ExecutionJobRepository jobs;

    private final StartExecutionJob start;

    private final FinishExecutionJob finish;

    private final com.flowops.application.execution.port.ProviderCalls calls;

    private final ProviderContracts contracts;

    private final ExecutionJobObservability observability;

    private final Clock clock;

    private final com.flowops.application.execution.port.ExecutionHeartbeats heartbeats;

    public ProcessExecutionJobs(ExecutionJobRepository jobs, StartExecutionJob start, FinishExecutionJob finish,
            com.flowops.application.execution.port.ProviderCalls calls, ProviderContracts contracts,
            ExecutionJobObservability observability, Clock clock,
            com.flowops.application.execution.port.ExecutionHeartbeats heartbeats) {
        this.jobs = jobs;
        this.start = start;
        this.finish = finish;
        this.calls = calls;
        this.contracts = contracts;
        this.observability = observability;
        this.clock = clock;
        this.heartbeats = heartbeats;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void execute() {
        for (var candidate : jobs.findCandidates(clock.instant(), ExecutionJobRepository.DEFAULT_BATCH_SIZE)) {
            if (Thread.currentThread().isInterrupted())
                return;
            try {
                var capacity = calls.capacity();
                if (capacity.executing() >= capacity.executionSlots())
                    return;
                var claim = start.start(candidate).orElse(null);
                if (claim == null)
                    continue;
                Map<String, Object> result = null;
                RuntimeException failure = null;
                // Catch only the external invocation. A failed finish must never
                // authorize a replay.
                try (var registration = heartbeats
                    .start(new com.flowops.domain.execution.ExecutionLease(claim.command().requestId(),
                            claim.job().getId(), claim.job().getAttemptCount(), claim.token()), claim.deadline())) {
                    try {
                        result = observability.runExecution(claim.job(),
                                () -> calls.execute(claim.command(), claim.providerContract(), claim.deadline()));
                    }
                    catch (RuntimeException externalFailure) {
                        failure = externalFailure;
                    }
                }
                boolean retrySafe = false;
                try {
                    retrySafe = claim.providerContract().idempotentRetries() && contracts.retrySafe(claim.job());
                }
                catch (RuntimeException changed) {
                    log.warn("Provider retry contract changed for job {}; automatic retry is disabled",
                            candidate.jobId());
                }
                finish.finish(claim, result, failure, retrySafe);
            }
            catch (RuntimeException failure) {
                log.error("Execution processing failed for job {}; committed starts remain protected",
                        candidate.jobId(), failure);
            }
        }
    }

}
