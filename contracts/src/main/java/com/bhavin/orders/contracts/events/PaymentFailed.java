package com.bhavin.orders.contracts.events;

import java.util.Objects;
import java.util.UUID;

public record PaymentFailed(UUID orderId, String reason) {
    public PaymentFailed {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reason, "reason");
    }
}
