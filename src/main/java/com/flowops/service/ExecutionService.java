package com.flowops.service;

import com.flowops.domain.ExecutionJob;
import com.flowops.domain.ExecutionJobRepository;
import com.flowops.domain.Request;
import com.flowops.domain.RequestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Enqueues execution jobs when a request is fully approved (PLAN §8, iteration 7).
 *
 * <p>Idempotent: repeated calls for the same (request, action) pair are
 * silently skipped via the {@code idempotency_key} unique constraint.
 *
 * <p>Called from {@code WorkflowEngine.decide()} when the request
 * transitions to APPROVED.
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
            String key = String.format("req:%d:%s", request.getId(), action);
            if (jobs.findByIdempotencyKey(key).isPresent()) {
                log.debug("EXEC SKIP (idempotent): {}", key);
                continue;
            }
            try {
                jobs.save(new ExecutionJob(key, request.getId(), action, correlationId));
                log.info("EXEC ENQUEUED: key={} action={}", key, action);
            } catch (DataIntegrityViolationException e) {
                log.debug("EXEC SKIP (concurrent duplicate): {}", key);
            }
        }
    }
}
