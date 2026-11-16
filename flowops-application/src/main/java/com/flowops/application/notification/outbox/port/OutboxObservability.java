package com.flowops.application.notification.outbox.port;

import com.flowops.application.notification.outbox.OutboxMessage;
import java.util.function.Supplier;

/**
 * Driven-side port for outbox-dispatch observability (PLAN §8).
 *
 * <p>Wraps the dispatch loop in the {@code outbox.dispatch} timer, each
 * delivery in an {@code outbox.deliver} span, and records the
 * sent / failed / dead-letter counters. Keeps the {@code DeliverOutbox}
 * use case free of OpenTelemetry and Micrometer types.
 */
public interface OutboxObservability {

    /** Runs {@code body} (the delivery loop) inside the dispatch timer. */
    void dispatch(Runnable body);

    /** Runs {@code body} inside a single {@code outbox.deliver} span. */
    <T> T runDelivery(OutboxMessage msg, Supplier<T> body);

    /** Record a successful delivery. */
    void recordSent(OutboxMessage msg);

    /** Record a failed delivery (plus dead-letter when retries are exhausted). */
    void recordFailed(OutboxMessage msg);
}
