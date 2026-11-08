package com.bhavin.orders.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OrderMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrderMetrics metrics = new OrderMetrics(registry);

    private double count(String outcome, String cause) {
        return registry.get("orders.saga.completed")
                .tag("outcome", outcome).tag("cause", cause).counter().count();
    }

    @Test
    void everyOutcomeExistsAtZeroBeforeTheFirstOrder() {
        assertThat(count("confirmed", "none")).isZero();
        assertThat(count("cancelled", "stock_rejected")).isZero();
        assertThat(count("cancelled", "payment_failed")).isZero();
    }

    @Test
    void confirmedAndCancelledAreCountedSeparately() {
        metrics.confirmed();
        metrics.confirmed();
        metrics.cancelled("payment_failed");

        assertThat(count("confirmed", "none")).isEqualTo(2.0);
        assertThat(count("cancelled", "payment_failed")).isEqualTo(1.0);
        assertThat(count("cancelled", "stock_rejected")).isZero();
    }

    @Test
    void insideATransactionTheCountWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.confirmed();
            assertThat(count("confirmed", "none")).isZero();

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            assertThat(count("confirmed", "none")).isEqualTo(1.0);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void aRolledBackTransactionCountsNothing() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.cancelled("payment_failed");

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertThat(count("cancelled", "payment_failed")).isZero();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
