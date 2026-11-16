package com.flowops.domain.execution.port;

import com.flowops.domain.request.Request;
import java.util.Map;

/**
 * Contract for executing side-effect actions on an approved request
 * (PLAN §8, iteration 7).
 *
 * <p>Implementations must be <b>idempotent</b>: calling {@code execute}
 * multiple times for the same (request, action) must produce the same
 * result without side effects.
 */
public interface ActionHandler {

    /**
     * Execute the named action against the request.
     *
     * @param request the approved request
     * @param action  the action code (PROVISIONING, PURCHASE_TICKET, ACCOUNTING_EXPORT, …)
     * @return a result payload to store in {@code execution_jobs.result}
     * @throws ActionExecutionException on failure (triggers retry)
     */
    Map<String, Object> execute(Request request, String action);

    /** Thrown when an action handler fails; the job worker retries with backoff. */
    class ActionExecutionException extends RuntimeException {
        public ActionExecutionException(String message) { super(message); }
        public ActionExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
