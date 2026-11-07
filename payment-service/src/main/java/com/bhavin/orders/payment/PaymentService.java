package com.bhavin.orders.payment;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Thrown when StockReserved arrives before the matching OrderCreated has been stored. */
    public static class OrderAmountUnknownException extends RuntimeException {
        public OrderAmountUnknownException(UUID orderId) {
            super("Amount for order " + orderId + " is not known yet");
        }
    }

    private final PaymentRepository repository;
    private final PaymentSimulator simulator;

    public PaymentService(PaymentRepository repository, PaymentSimulator simulator) {
        this.repository = repository;
        this.simulator = simulator;
    }

    /** Idempotent: the first decision for an order is stored and returned on every later call. */
    public Payment pay(UUID orderId) {
        Optional<Payment> existing = repository.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Order {} already has a {} payment, reusing it", orderId, existing.get().status());
            return existing.get();
        }

        OrderAmount total = repository.findOrderAmount(orderId)
                .orElseThrow(() -> new OrderAmountUnknownException(orderId));
        PaymentSimulator.Decision decision = simulator.decide(orderId);
        Payment payment = new Payment(
                UUID.randomUUID(),
                orderId,
                decision.approved() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED,
                total.amount(),
                total.currency(),
                decision.approved() ? null : decision.reason());

        if (repository.insertIfAbsent(payment)) {
            return payment;
        }
        return repository.findByOrderId(orderId).orElseThrow(
                () -> new IllegalStateException("Payment conflict but no record for " + orderId));
    }

    public Optional<Payment> find(UUID orderId) {
        return repository.findByOrderId(orderId);
    }
}
