package com.bhavin.orders.order;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Carries the current trace across the outbox. The relay publishes later, on another thread, so
 * the trace would be lost; instead the W3C traceparent is stored with the row (capture) and
 * restored around the Kafka send (runInContext). The Kafka instrumentation then continues the
 * same trace into the next service.
 */
@Component
public class TraceContextCodec {

    static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public TraceContextCodec(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    private TraceContextCodec() {
        this.tracer = null;
        this.propagator = null;
    }

    /** A codec that does nothing: for unit tests that do not run tracing. */
    public static TraceContextCodec disabled() {
        return new TraceContextCodec();
    }

    /** The traceparent of the span running on this thread, or null when there is none. */
    public String capture() {
        if (tracer == null) {
            return null;
        }
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Runs the action inside a span that continues the stored trace. Without one, just runs it. */
    public void runInContext(String traceparent, Runnable action) {
        if (tracer == null || traceparent == null) {
            action.run();
            return;
        }
        Span span = propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get)
                .name("outbox relay")
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            action.run();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
