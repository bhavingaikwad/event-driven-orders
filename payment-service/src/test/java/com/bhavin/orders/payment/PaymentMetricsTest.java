package com.bhavin.orders.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PaymentMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<CircuitBreaker.State> state =
            new AtomicReference<>(CircuitBreaker.State.CLOSED);
    private final PaymentMetrics metrics = new PaymentMetrics(registry, state::get);

    private double count(String outcome) {
        return registry.get("payments.processed").tag("outcome", outcome).counter().count();
    }

    private double breakerGauge() {
        return registry.get("payment.breaker.state").gauge().value();
    }

    @Test
    void approvedDeclinedAndOpenCircuitAreCountedSeparately() {
        metrics.record(new PaymentSimulator.Decision(true, null));
        metrics.record(new PaymentSimulator.Decision(false, "Card declined (simulated)"));
        metrics.record(new PaymentSimulator.Decision(false, "Card declined (simulated)"));
        metrics.record(new PaymentSimulator.Decision(false, GatewayCircuitBreaker.REASON_OPEN));

        assertThat(count("approved")).isEqualTo(1.0);
        assertThat(count("declined")).isEqualTo(2.0);
        assertThat(count("rejected_circuit_open")).isEqualTo(1.0);
    }

    @Test
    void breakerGaugeFollowsTheState() {
        assertThat(breakerGauge()).isEqualTo(0.0);
        state.set(CircuitBreaker.State.HALF_OPEN);
        assertThat(breakerGauge()).isEqualTo(1.0);
        state.set(CircuitBreaker.State.OPEN);
        assertThat(breakerGauge()).isEqualTo(2.0);
    }
}
