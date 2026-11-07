package com.bhavin.orders.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String customerId,
        String customerRegion,
        String currency,
        BigDecimal totalAmount,
        OrderStatus status,
        UUID correlationId,
        Instant createdAt,
        List<Item> items) {

    public record Item(String sku, int quantity, BigDecimal unitPrice) {
    }

    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.getId(), o.getCustomerId(), o.getCustomerRegion(), o.getCurrency(),
                o.getTotalAmount(), o.getStatus(), o.getCorrelationId(), o.getCreatedAt(),
                o.getItems().stream()
                        .map(i -> new Item(i.getSku(), i.getQuantity(), i.getUnitPrice()))
                        .toList());
    }
}
