package com.bhavin.orders.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counts orders that reached a final state. Low-cardinality tags only: never an order id. */
@Component
public class OrderMetrics {

    private static final String NAME = "orders.saga.completed";

    private final MeterRegistry registry;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
        counter("confirmed", "none");
        counter("cancelled", "stock_rejected");
        counter("cancelled", "payment_failed");
    }

    public void confirmed() {
        counter("confirmed", "none").increment();
    }

    public void cancelled(String cause) {
        counter("cancelled", cause).increment();
    }

    private Counter counter(String outcome, String cause) {
        return Counter.builder(NAME)
                .description("Orders that reached a final state, by outcome and cause")
                .tag("outcome", outcome)
                .tag("cause", cause)
                .register(registry);
    }
}
