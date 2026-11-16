package com.flowops.application.execution;

import com.flowops.domain.ExecutionJob;
import com.flowops.domain.port.ExecutionJobRepository;
import com.flowops.domain.OutboxMessage;
import com.flowops.domain.Request;
import com.flowops.domain.RequestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Enqueues execution jobs when a request is fully approved (PLAN §8, iteration 7).
 *
 * <p>Idempotent: repeated calls for the same (request, action) pair are
 * silently skipped via the {@code idempotency_key} unique constraint.
 *
 * <p>Called by the synchronous {@code RequestApproved} event handler within
 * the approval transaction.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutionJobRepository jobs;

    public ExecutionService(ExecutionJobRepository jobs) {
        this.jobs = jobs;
    }

    /**
     * Create execution jobs for every action declared on the request's type.
     * No-op if the type has no execution actions (e.g. POLICY_EXCEPTION).
     */
    public void enqueueJobs(Request request) {
        RequestType type = request.getType();
        List<String> actions = type.getExecutionActions();
        if (actions == null || actions.isEmpty()) {
            log.debug("EXEC SKIP (no actions): request={}", request.getId());
            return;
        }

        String correlationId = request.getCorrelationId();
        for (String action : actions) {
            String key = String.format(OutboxMessage.KEY_FORMAT_JOB, request.getId(), action);
            jobs.saveIfAbsent(new ExecutionJob(key, request.getId(), action, correlationId))
                    .ifPresentOrElse(
                            saved -> log.info("EXEC ENQUEUED: key={} action={}", key, action),
                            () -> log.debug("EXEC SKIP (duplicate): {}", key));
        }
    }
}
