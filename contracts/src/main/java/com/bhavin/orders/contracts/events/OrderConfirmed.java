package com.bhavin.orders.contracts.events;

import java.util.Objects;
import java.util.UUID;

public record OrderConfirmed(UUID orderId) {
    public OrderConfirmed {
        Objects.requireNonNull(orderId, "orderId");
    }
}
