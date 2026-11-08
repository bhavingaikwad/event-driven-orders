package com.bhavin.orders.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Payment outcome counters and the gateway breaker state. Low-cardinality tags only. */
@Component
public class PaymentMetrics {

    private static final String PAYMENTS = "payments.processed";

    private final MeterRegistry registry;
    private final Supplier<CircuitBreaker.State> breakerState;

    @Autowired
    public PaymentMetrics(MeterRegistry registry, GatewayCircuitBreaker breaker) {
        this(registry, breaker::state);
    }

    PaymentMetrics(MeterRegistry registry, Supplier<CircuitBreaker.State> breakerState) {
        this.registry = registry;
        this.breakerState = breakerState;
        Gauge.builder("payment.breaker.state", this, PaymentMetrics::stateCode)
                .description("Gateway circuit breaker: 0 closed, 1 half open, 2 open, 3 other")
                .register(registry);
        counter("approved");
        counter("declined");
        counter("rejected_circuit_open");
    }

    public void record(PaymentSimulator.Decision decision) {
        if (decision.approved()) {
            counter("approved").increment();
        } else if (GatewayCircuitBreaker.REASON_OPEN.equals(decision.reason())) {
            counter("rejected_circuit_open").increment();
        } else {
            counter("declined").increment();
        }
    }

    private double stateCode() {
        return switch (breakerState.get()) {
            case CLOSED -> 0.0;
            case HALF_OPEN -> 1.0;
            case OPEN -> 2.0;
            default -> 3.0;
        };
    }

    private Counter counter(String outcome) {
        return Counter.builder(PAYMENTS)
                .description("Payments decided, by outcome (simulated gateway)")
                .tag("outcome", outcome)
                .register(registry);
    }
}
