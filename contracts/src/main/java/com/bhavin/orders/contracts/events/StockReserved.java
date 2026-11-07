package com.bhavin.orders.contracts.events;

import java.util.Objects;
import java.util.UUID;

public record StockReserved(UUID orderId, UUID reservationId) {
    public StockReserved {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reservationId, "reservationId");
    }
}
