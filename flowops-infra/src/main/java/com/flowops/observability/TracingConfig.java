package com.flowops.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry wiring (PLAN §8, iteration 8).
 *
 * <p>Spans flow to a composite of:
 * <ul>
 *   <li>{@link InMemorySpanExporter} — bounded ring buffer, asserted by tests
 *       and browsable in dev without a collector;</li>
 *   <li>{@link LoggingSpanExporter} — console output, so local runs show the
 *       lifecycle trace next to the JSON logs.</li>
 * </ul>
 *
 * <p>SimpleSpanProcessor (synchronous export) is fine at this scale and keeps
 * span ordering deterministic for tests.
 */
@Configuration
public class TracingConfig {

    @Bean
    public InMemorySpanExporter flowopsInMemorySpanExporter() {
        return new InMemorySpanExporter();
    }

    @Bean
    public SdkTracerProvider flowopsTracerProvider(InMemorySpanExporter inMemory) {
        Resource resource = Resource.create(Attributes.builder()
                .put(AttributeKey.stringKey("service.name"), "flowops")
                .put(AttributeKey.stringKey("service.version"), "0.1.0")
                .build());
        SpanExporter exporter = SpanExporter.composite(inMemory, new LoggingSpanExporter());
        return SdkTracerProvider.builder()
                .addResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    @Bean
    public Tracer flowopsTracer(SdkTracerProvider provider) {
        return provider.get("com.flowops");
    }
}
