package com.flowops.observability;

import com.flowops.application.port.RequestLifecycleObservability;
import com.flowops.domain.DecisionAction;
import com.flowops.domain.Request;
import com.flowops.domain.RequestStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * OpenTelemetry + Micrometer implementation of the request-lifecycle
 * observability port (PLAN §8): one span + counter per lifecycle event.
 *
 * <p>Keeping this in the observability module means the application layer
 * only sees the framework-free {@link RequestLifecycleObservability} port —
 * no {@code io.opentelemetry} or {@code io.micrometer} types leak into
 * services or use cases.
 */
@Component
public class RequestLifecycleObservabilityAdapter implements RequestLifecycleObservability {

    private final FlowOpsSpans spans;
    private final MeterRegistry metrics;

    public RequestLifecycleObservabilityAdapter(FlowOpsSpans spans, MeterRegistry metrics) {
        this.spans = spans;
        this.metrics = metrics;
    }

    @Override
    public Request submit(long requestId, String user, Supplier<Request> operation) {
        Request submitted = spans.run(SpanNames.REQUEST_SUBMIT, Map.of(
                "request.id", String.valueOf(requestId),
                "user", user),
                operation);
        metrics.counter(MetricNames.REQUEST_SUBMITTED).increment();
        return submitted;
    }

    @Override
    public Request decide(long requestId, String user, DecisionAction action, Supplier<Request> operation) {
        Request decided = spans.run(SpanNames.REQUEST_DECIDE, Map.of(
                "request.id", String.valueOf(requestId),
                "user", user,
                "action", action.name()),
                operation);
        metrics.counter(MetricNames.REQUEST_DECIDED, "action", action.name()).increment();
        if (decided.getStatus() == RequestStatus.APPROVED) {
            metrics.counter(MetricNames.REQUEST_APPROVED).increment();
        }
        return decided;
    }

    @Override
    public Request close(long requestId, String user, Supplier<Request> operation) {
        Request closed = spans.run(SpanNames.REQUEST_CLOSE, Map.of(
                "request.id", String.valueOf(requestId),
                "user", user),
                operation);
        metrics.counter(MetricNames.REQUEST_CLOSED).increment();
        return closed;
    }
}
