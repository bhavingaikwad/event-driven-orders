package com.bhavin.orders.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.simulation")
public class PaymentProperties {

    /** Simulated share of declined payments, 0.0 to 1.0. */
    private double failureRate = 0.2;

    public double getFailureRate() {
        return failureRate;
    }

    public void setFailureRate(double failureRate) {
        this.failureRate = failureRate;
    }
}
