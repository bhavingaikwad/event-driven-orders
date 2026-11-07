package com.bhavin.orders.contracts.events;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record PaymentSucceeded(UUID orderId, UUID paymentId, BigDecimal amount, String currency) {
    public PaymentSucceeded {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }
}
