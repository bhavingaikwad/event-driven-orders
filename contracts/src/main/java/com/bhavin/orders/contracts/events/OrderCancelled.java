package com.bhavin.orders.contracts.events;

import java.util.Objects;
import java.util.UUID;

public record OrderCancelled(UUID orderId, String reason) {
    public OrderCancelled {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reason, "reason");
    }
}
