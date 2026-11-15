package com.flowops.application.port;

import com.flowops.domain.DecisionAction;
import com.flowops.domain.Request;

import java.util.function.Supplier;

/**
 * Driven-side port for request-lifecycle observability (PLAN §8).
 *
 * <p>The application layer emits exactly three lifecycle events — submit,
 * decide, close. The adapter ({@code com.flowops.observability}) wraps each
 * in a trace span and records the matching metrics, so services stay free of
 * OpenTelemetry / Micrometer types.
 */
public interface RequestLifecycleObservability {

    /**
     * Runs the submit use case ({@code DRAFT → IN_REVIEW}) as a lifecycle event.
     */
    Request submit(long requestId, String user, Supplier<Request> operation);

    /**
     * Runs a step decision (APPROVE / REJECT / DELEGATE) as a lifecycle event;
     * the adapter also records the "approved" metric when the request reaches
     * its APPROVED state.
     */
    Request decide(long requestId, String user, DecisionAction action, Supplier<Request> operation);

    /**
     * Runs the close use case (terminal) as a lifecycle event.
     */
    Request close(long requestId, String user, Supplier<Request> operation);
}
