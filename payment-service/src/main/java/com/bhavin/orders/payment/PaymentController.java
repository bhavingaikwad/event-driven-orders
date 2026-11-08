package com.bhavin.orders.payment;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PaymentController {

    private final PaymentService payments;
    private final PaymentSimulator simulator;
    private final GatewayCircuitBreaker breaker;

    public PaymentController(PaymentService payments, PaymentSimulator simulator, GatewayCircuitBreaker breaker) {
        this.payments = payments;
        this.simulator = simulator;
        this.breaker = breaker;
    }

    @GetMapping("/payments/{orderId}")
    public ResponseEntity<Payment> payment(@PathVariable("orderId") UUID orderId) {
        return payments.find(orderId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/simulation")
    public Map<String, Object> simulation() {
        return Map.of("failureRate", simulator.failureRate(),
                "transientFailuresLeft", simulator.transientFailuresLeft(),
                "gatewayDown", simulator.gatewayDown(),
                "gatewayLatencyMs", simulator.gatewayLatencyMs());
    }

    /** Failure injection: change the share of declined payments without restarting. */
    @PutMapping("/simulation/failure-rate")
    public Map<String, Object> setFailureRate(@RequestParam("value") double value) {
        try {
            simulator.setFailureRate(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return Map.of("failureRate", simulator.failureRate());
    }

    /** Failure injection: the next N payment attempts throw, to exercise retries and the DLQ. */
    @PutMapping("/simulation/transient-failures")
    public Map<String, Object> setTransientFailures(@RequestParam("count") int count) {
        try {
            simulator.setTransientFailures(count);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return Map.of("transientFailuresLeft", simulator.transientFailuresLeft());
    }

    /** Failure injection: make the simulated payment gateway unreachable. */
    @PutMapping("/simulation/gateway-down")
    public Map<String, Object> setGatewayDown(@RequestParam("value") boolean value) {
        simulator.setGatewayDown(value);
        return Map.of("gatewayDown", simulator.gatewayDown());
    }

    /** Failure injection: make every gateway call take this many milliseconds. */
    @PutMapping("/simulation/gateway-latency")
    public Map<String, Object> setGatewayLatency(@RequestParam("ms") long ms) {
        try {
            simulator.setGatewayLatencyMs(ms);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return Map.of("gatewayLatencyMs", simulator.gatewayLatencyMs());
    }

    @GetMapping("/circuit-breaker")
    public Map<String, Object> circuitBreaker() {
        return breaker.status();
    }
}
