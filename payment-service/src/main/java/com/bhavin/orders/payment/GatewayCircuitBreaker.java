package com.bhavin.orders.payment;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Circuit breaker around the payment gateway. Gateway outages and slow calls count as failures;
 * a declined card is a normal answer and does not. While the breaker is open, payments are rejected
 * immediately and the gateway is not called.
 */
@Component
public class GatewayCircuitBreaker {

    static final String REASON_OPEN = "Payment gateway unavailable (circuit open)";

    private static final Logger log = LoggerFactory.getLogger(GatewayCircuitBreaker.class);

    private final PaymentSimulator gateway;
    private final CircuitBreaker breaker;
    private final Deque<String> transitions = new ConcurrentLinkedDeque<>();

    public GatewayCircuitBreaker(PaymentSimulator gateway,
                                 @Value("${payment.breaker.wait-open-ms:5000}") long waitOpenMs,
                                 @Value("${payment.breaker.slow-call-ms:1000}") long slowCallMs) {
        this.gateway = gateway;
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50f)
                .slowCallDurationThreshold(Duration.ofMillis(slowCallMs))
                .slowCallRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofMillis(waitOpenMs))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(e -> e instanceof GatewayUnavailableException)
                .build();
        this.breaker = CircuitBreaker.of("payment-gateway", config);
        this.breaker.getEventPublisher().onStateTransition(event -> {
            log.warn("Circuit breaker payment-gateway: {}", event.getStateTransition());
            transitions.addLast(Instant.now() + " " + event.getStateTransition().name());
            while (transitions.size() > 20) {
                transitions.pollFirst();
            }
        });
    }

    /** Charges through the breaker. An open breaker answers with a rejection instead of calling the gateway. */
    public PaymentSimulator.Decision charge(UUID orderId) {
        try {
            return breaker.executeSupplier(() -> gateway.charge(orderId));
        } catch (CallNotPermittedException e) {
            return new PaymentSimulator.Decision(false, REASON_OPEN);
        }
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    public Map<String, Object> status() {
        CircuitBreaker.Metrics metrics = breaker.getMetrics();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", breaker.getState().name());
        status.put("successfulCalls", metrics.getNumberOfSuccessfulCalls());
        status.put("failedCalls", metrics.getNumberOfFailedCalls());
        status.put("slowCalls", metrics.getNumberOfSlowCalls());
        status.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
        status.put("transitions", new ArrayList<>(transitions));
        return status;
    }
}
