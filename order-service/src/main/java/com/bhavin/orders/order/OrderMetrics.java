package com.bhavin.orders.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counts orders that reached a final state. Low-cardinality tags only: never an order id.
 * Inside a transaction the count waits for the commit, so a rolled-back and retried attempt
 * is not counted twice.
 */
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
        whenCommitted(() -> counter("confirmed", "none").increment());
    }

    public void cancelled(String cause) {
        whenCommitted(() -> counter("cancelled", cause).increment());
    }

    private static void whenCommitted(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private Counter counter(String outcome, String cause) {
        return Counter.builder(NAME)
                .description("Orders that reached a final state, by outcome and cause")
                .tag("outcome", outcome)
                .tag("cause", cause)
                .register(registry);
    }
}
