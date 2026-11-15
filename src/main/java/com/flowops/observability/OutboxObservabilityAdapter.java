package com.flowops.observability;

import com.flowops.application.port.OutboxObservability;
import com.flowops.domain.OutboxMessage;
import com.flowops.domain.OutboxStatus;
import com.flowops.domain.port.OutboxMessageRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Outbox-dispatch observability (PLAN §8): the {@code outbox.dispatch}
 * timer, one {@code outbox.deliver} span per message, sent / failed /
 * dead-letter counters, and the pending gauge (moved here from
 * {@code OutboxDispatcher} so the worker stays a thin scheduling shell).
 */
@Component
public class OutboxObservabilityAdapter implements OutboxObservability {

    private final FlowOpsSpans spans;
    private final MeterRegistry metrics;

    public OutboxObservabilityAdapter(FlowOpsSpans spans,
                                      MeterRegistry metrics,
                                      OutboxMessageRepository outbox) {
        this.spans = spans;
        this.metrics = metrics;
        Gauge.builder(MetricNames.OUTBOX_PENDING, outbox,
                r -> r.countByStatus(OutboxStatus.PENDING) + r.countByStatus(OutboxStatus.FAILED))
                .description("Outbox messages awaiting delivery (PENDING + FAILED)")
                .register(metrics);
    }

    @Override
    public void dispatch(Runnable body) {
        Timer.builder(MetricNames.OUTBOX_DISPATCH)
                .description("Time to deliver one outbox batch")
                .register(metrics)
                .record(body);
    }

    @Override
    public <T> T runDelivery(OutboxMessage msg, Supplier<T> body) {
        return spans.run(SpanNames.OUTBOX_DELIVER, Map.of(
                "outbox.key", msg.getIdempotencyKey(),
                "channel", msg.getChannel(),
                "request.id", String.valueOf(msg.getRecipientId())), body);
    }

    @Override
    public void recordSent(OutboxMessage msg) {
        metrics.counter(MetricNames.OUTBOX_MESSAGE_SENT, "channel", msg.getChannel()).increment();
    }

    @Override
    public void recordFailed(OutboxMessage msg) {
        metrics.counter(MetricNames.OUTBOX_MESSAGE_FAILED, "channel", msg.getChannel()).increment();
        if (msg.getStatus() == OutboxStatus.DEAD_LETTER) {
            metrics.counter(MetricNames.OUTBOX_MESSAGE_DEAD_LETTER, "channel", msg.getChannel()).increment();
        }
    }
}
