package com.flowops.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Small helper that opens one span per request-lifecycle event (PLAN §8, iteration 8) and
 * records outcome/status on the way out.
 *
 * <p>
 * Convention: span names are dotted lifecycle events ({@code request.submit},
 * {@code request.decide}, {@code job.execute}, {@code outbox.deliver}, {@code sla.scan});
 * attributes use dotted keys ({@code request.id}, {@code user}, {@code outcome}, …).
 */
@Component
public class FlowOpsSpans {

    private final Tracer tracer;

    public FlowOpsSpans(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Run {@code body} inside a span. The span is marked OK on success, ERROR (with the
     * exception recorded) on failure, and always ended.
     */
    public <T> T run(String spanName, Map<String, String> attributes, Supplier<T> body) {
        Span span = start(spanName, attributes);
        try {
            T result = body.get();
            span.setStatus(StatusCode.OK);
            return result;
        }
        catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        }
        finally {
            span.end();
        }
    }

    /** Open a span (caller ends it), for long-running work like a scan loop. */
    public Span start(String spanName, Map<String, String> attributes) {
        SpanBuilder builder = tracer.spanBuilder(spanName);
        if (attributes != null) {
            attributes.forEach((k, v) -> builder.setAttribute(k, v == null ? "-" : v));
        }
        return builder.startSpan();
    }

    public void success(Span span) {
        span.setStatus(StatusCode.OK);
        span.end();
    }

    public void error(Span span, Throwable t) {
        span.recordException(t);
        span.setStatus(StatusCode.ERROR, t.getMessage());
        span.end();
    }

}
