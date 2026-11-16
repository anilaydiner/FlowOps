package com.flowops.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bounded in-memory span sink (PLAN §8, iteration 8).
 *
 * <p>Two purposes:
 * <ul>
 *   <li><b>Tests</b> — assert that lifecycle spans were produced
 *       (name, attributes, status).</li>
 *   <li><b>Dev/ops</b> — an always-available ring buffer of recent spans so a
 *       collector isn't required to see traces locally (the logging exporter
 *       mirrors them to the console as well).</li>
 * </ul>
 *
 * <p>Bounded: keeps at most {@link #MAX_SPANS} most recent finished spans so
 * long-running processes cannot grow unbounded.
 */
public class InMemorySpanExporter implements SpanExporter {

    public static final int MAX_SPANS = 2000;

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> collection) {
        for (SpanData span : collection) {
            spans.add(span);
        }
        while (spans.size() > MAX_SPANS) {
            spans.remove(0);
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        spans.clear();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public void close() {
        spans.clear();
    }

    /** All finished spans retained so far (oldest first). */
    public List<SpanData> finishedSpans() {
        return List.copyOf(spans);
    }

    public void reset() {
        spans.clear();
    }
}
