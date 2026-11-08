package com.bhavin.orders.payment;

/** The (simulated) payment gateway cannot be reached. Counts as a failure for the circuit breaker. */
public class GatewayUnavailableException extends RuntimeException {
    public GatewayUnavailableException() {
        super("Payment gateway unavailable (simulated)");
    }
}
