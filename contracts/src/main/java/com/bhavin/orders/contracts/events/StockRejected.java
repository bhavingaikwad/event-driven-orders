package com.bhavin.orders.contracts.events;

import java.util.Objects;
import java.util.UUID;

public record StockRejected(UUID orderId, String reason) {
    public StockRejected {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reason, "reason");
    }
}
