package com.bhavin.orders.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayCircuitBreakerTest {

    private static PaymentSimulator simulator(double failureRate) {
        PaymentProperties properties = new PaymentProperties();
        properties.setFailureRate(failureRate);
        return new PaymentSimulator(properties);
    }

    private static GatewayCircuitBreaker breaker(PaymentSimulator simulator, long waitOpenMs, long slowCallMs) {
        return new GatewayCircuitBreaker(simulator, waitOpenMs, slowCallMs);
    }

    private static UUID id() {
        return UUID.randomUUID();
    }

    private static void openByOutage(PaymentSimulator simulator, GatewayCircuitBreaker breaker) {
        simulator.setGatewayDown(true);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> breaker.charge(id())).isInstanceOf(GatewayUnavailableException.class);
        }
    }

    @Test
    void healthyGatewayKeepsTheBreakerClosed() {
        GatewayCircuitBreaker breaker = breaker(simulator(0.0), 10_000, 1_000);
        for (int i = 0; i < 10; i++) {
            assertThat(breaker.charge(id()).approved()).isTrue();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void declinedPaymentsAreBusinessResultsNotFailures() {
        GatewayCircuitBreaker breaker = breaker(simulator(1.0), 10_000, 1_000);
        for (int i = 0; i < 10; i++) {
            PaymentSimulator.Decision decision = breaker.charge(id());
            assertThat(decision.approved()).isFalse();
            assertThat(decision.reason()).isEqualTo("Card declined (simulated)");
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opensAfterRepeatedGatewayFailures() {
        PaymentSimulator simulator = simulator(0.0);
        GatewayCircuitBreaker breaker = breaker(simulator, 10_000, 1_000);
        openByOutage(simulator, breaker);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void openBreakerRejectsWithoutCallingTheGateway() {
        PaymentSimulator simulator = simulator(0.0);
        GatewayCircuitBreaker breaker = breaker(simulator, 10_000, 1_000);
        openByOutage(simulator, breaker);
        simulator.setGatewayDown(false);

        PaymentSimulator.Decision decision = breaker.charge(id());

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).isEqualTo(GatewayCircuitBreaker.REASON_OPEN);
    }

    @Test
    void recoversThroughHalfOpenWhenTheGatewayIsBack() throws InterruptedException {
        PaymentSimulator simulator = simulator(0.0);
        GatewayCircuitBreaker breaker = breaker(simulator, 100, 1_000);
        openByOutage(simulator, breaker);
        simulator.setGatewayDown(false);
        Thread.sleep(250);

        assertThat(breaker.charge(id()).approved()).isTrue();
        assertThat(breaker.charge(id()).approved()).isTrue();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void slowCallsOpenTheBreaker() {
        PaymentSimulator simulator = simulator(0.0);
        simulator.setGatewayLatencyMs(100);
        GatewayCircuitBreaker breaker = breaker(simulator, 10_000, 50);
        for (int i = 0; i < 3; i++) {
            assertThat(breaker.charge(id()).approved()).isTrue();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.charge(id()).reason()).isEqualTo(GatewayCircuitBreaker.REASON_OPEN);
    }

    @Test
    void statusReportsStateAndTransitions() {
        PaymentSimulator simulator = simulator(0.0);
        GatewayCircuitBreaker breaker = breaker(simulator, 10_000, 1_000);
        openByOutage(simulator, breaker);

        Map<String, Object> status = breaker.status();

        assertThat(status.get("state")).isEqualTo("OPEN");
        assertThat(((List<?>) status.get("transitions")).toString()).contains("CLOSED_TO_OPEN");
    }
}
