package com.bhavin.orders.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentSimulatorTest {

    private static PaymentSimulator simulatorWithRate(double rate) {
        PaymentProperties properties = new PaymentProperties();
        properties.setFailureRate(rate);
        return new PaymentSimulator(properties);
    }

    private static UUID randomId(Random random) {
        return new UUID(random.nextLong(), random.nextLong());
    }

    @Test
    void rateZeroAlwaysApproves() {
        PaymentSimulator simulator = simulatorWithRate(0.0);
        Random random = new Random(1);
        for (int i = 0; i < 1000; i++) {
            assertThat(simulator.decide(randomId(random)).approved()).isTrue();
        }
    }

    @Test
    void rateOneAlwaysDeclines() {
        PaymentSimulator simulator = simulatorWithRate(1.0);
        Random random = new Random(2);
        for (int i = 0; i < 1000; i++) {
            assertThat(simulator.decide(randomId(random)).approved()).isFalse();
        }
    }

    @Test
    void sameOrderAlwaysGetsTheSameDecision() {
        PaymentSimulator simulator = simulatorWithRate(0.5);
        UUID orderId = new UUID(123L, 456L);
        boolean first = simulator.decide(orderId).approved();
        for (int i = 0; i < 100; i++) {
            assertThat(simulator.decide(orderId).approved()).isEqualTo(first);
        }
    }

    @Test
    void declinedShareTracksTheConfiguredRate() {
        PaymentSimulator simulator = simulatorWithRate(0.2);
        Random random = new Random(42);
        int total = 10_000;
        int declined = 0;
        for (int i = 0; i < total; i++) {
            if (!simulator.decide(randomId(random)).approved()) {
                declined++;
            }
        }
        assertThat(declined / (double) total).isBetween(0.17, 0.23);
    }

    @Test
    void rejectsRatesOutsideZeroToOne() {
        PaymentSimulator simulator = simulatorWithRate(0.2);
        assertThatThrownBy(() -> simulator.setFailureRate(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> simulator.setFailureRate(-0.1)).isInstanceOf(IllegalArgumentException.class);
    }
}
