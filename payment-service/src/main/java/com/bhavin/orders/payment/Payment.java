package com.bhavin.orders.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record Payment(UUID paymentId, UUID orderId, PaymentStatus status,
                      BigDecimal amount, String currency, String failureReason) {
}
