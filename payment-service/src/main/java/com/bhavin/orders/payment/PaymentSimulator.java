package com.bhavin.orders.payment;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SIMULATED payment gateway. The decision depends only on the orderId and the current failure rate.
 * Outage and latency switches let us exercise retries, the dead-letter topic and the circuit breaker.
 */
@Component
public class PaymentSimulator {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulator.class);

    public record Decision(boolean approved, String reason) {
        static Decision approve() {
            return new Decision(true, null);
        }

        static Decision decline() {
            return new Decision(false, "Card declined (simulated)");
        }
    }

    /** Thrown on purpose to exercise the retry and dead-letter policy. */
    public static class SimulatedTransientException extends RuntimeException {
        public SimulatedTransientException(UUID orderId, int left) {
            super("Simulated transient failure for order " + orderId + " (" + left + " left)");
        }
    }

    private volatile double failureRate;
    private volatile boolean gatewayDown;
    private volatile long gatewayLatencyMs;
    private final AtomicInteger transientFailuresLeft = new AtomicInteger();

    public PaymentSimulator(PaymentProperties properties) {
        setFailureRate(properties.getFailureRate());
    }

    public double failureRate() {
        return failureRate;
    }

    public void setFailureRate(double rate) {
        if (Double.isNaN(rate) || rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("failure rate must be between 0.0 and 1.0, got " + rate);
        }
        this.failureRate = rate;
    }

    public boolean gatewayDown() {
        return gatewayDown;
    }

    public void setGatewayDown(boolean down) {
        this.gatewayDown = down;
    }

    public long gatewayLatencyMs() {
        return gatewayLatencyMs;
    }

    public void setGatewayLatencyMs(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("latency must be >= 0, got " + ms);
        }
        this.gatewayLatencyMs = ms;
    }

    public int transientFailuresLeft() {
        return transientFailuresLeft.get();
    }

    public void setTransientFailures(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0, got " + count);
        }
        transientFailuresLeft.set(count);
    }

    /** Throws while the counter is above zero, then behaves normally. */
    public void maybeFailTransiently(UUID orderId) {
        int before = transientFailuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0);
        if (before > 0) {
            log.warn("Injecting transient failure for order {} ({} left)", orderId, before - 1);
            throw new SimulatedTransientException(orderId, before - 1);
        }
    }

    /** The call to the gateway: may be slow, may be down, otherwise returns the decision. */
    public Decision charge(UUID orderId) {
        long latency = gatewayLatencyMs;
        if (latency > 0) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while calling the gateway", e);
            }
        }
        if (gatewayDown) {
            throw new GatewayUnavailableException();
        }
        return decide(orderId);
    }

    public Decision decide(UUID orderId) {
        double score = Math.floorMod(orderId.hashCode(), 10_000) / 10_000.0;
        return score < failureRate ? Decision.decline() : Decision.approve();
    }
}
