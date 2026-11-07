package com.bhavin.orders.payment;

import java.math.BigDecimal;

public record OrderAmount(BigDecimal amount, String currency) {
}
