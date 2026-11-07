package com.bhavin.orders.contracts.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrderCreated(
        UUID orderId,
        String customerId,
        String customerRegion,
        List<Item> items,
        BigDecimal totalAmount,
        String currency) {

    public record Item(String sku, int quantity, BigDecimal unitPrice) {
        public Item {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(unitPrice, "unitPrice");
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be >= 1");
            }
            if (unitPrice.signum() < 0) {
                throw new IllegalArgumentException("unitPrice must be >= 0");
            }
        }
    }

    public OrderCreated {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(customerRegion, "customerRegion");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(currency, "currency");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (totalAmount.signum() < 0) {
            throw new IllegalArgumentException("totalAmount must be >= 0");
        }
        items = List.copyOf(items);
    }
}
