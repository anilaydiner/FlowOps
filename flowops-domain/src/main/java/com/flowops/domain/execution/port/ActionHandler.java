package com.flowops.domain.execution.port;

import com.flowops.domain.execution.ActionCommand;
import java.util.Map;

/** External adapter contract. Invoked only after durable start authorization, outside transactions. */
public interface ActionHandler {

    /** Execute frozen input using the stable logical-action idempotency key. */
    Map<String, Object> execute(ActionCommand command);

    /** Declared by the concrete adapter for each supported action; null means unsupported. No I/O. */
    default com.flowops.domain.execution.ProviderContract contract(String action) { return null; }

    /** Network adapters apply the supplied connect/read/total budget; outer callers also bound waiting. */
    default Map<String,Object> execute(ActionCommand command,com.flowops.domain.execution.ProviderCall call) { return execute(command); }

    /** Pure capability metadata, with no I/O. True only when the stable key prevents duplicate external effects. */
    default boolean supportsIdempotentRetries() { return false; }

    /**
     * Read-only lookup outside transactions. Missing/not-found is UNKNOWN unless the
     * provider can fence all old calls; it is not proof that replay is safe.
     * SUCCEEDED and SAFE_TO_RETRY require an authoritative provider reference.
     */
    default com.flowops.domain.execution.ProviderObservation reconcile(com.flowops.domain.execution.ProviderQuery query) {
        return com.flowops.domain.execution.ProviderObservation.unknown();
    }
    default com.flowops.domain.execution.ProviderObservation reconcile(com.flowops.domain.execution.ProviderQuery query,
                                                                       com.flowops.domain.execution.ProviderCall call) { return reconcile(query); }

    /** Adapter failure. Automatic retry additionally requires an explicit idempotency guarantee. */
    class ActionExecutionException extends RuntimeException {
        public ActionExecutionException(String message) { super(message); }
        public ActionExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
