package com.bhavin.orders.payment;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** SIMULATED payment gateway. The outcome depends only on the orderId and the current failure rate. */
@Component
public class PaymentSimulator {

    public record Decision(boolean approved, String reason) {
        static Decision approve() {
            return new Decision(true, null);
        }

        static Decision decline() {
            return new Decision(false, "Card declined (simulated)");
        }
    }

    private volatile double failureRate;

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

    public Decision decide(UUID orderId) {
        double score = Math.floorMod(orderId.hashCode(), 10_000) / 10_000.0;
        return score < failureRate ? Decision.decline() : Decision.approve();
    }
}
