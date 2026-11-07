package com.bhavin.orders.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record CreateOrderRequest(
        @NotBlank String customerId,
        @NotBlank String customerRegion,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotEmpty @Valid List<Item> items) {

    public record Item(
            @NotBlank String sku,
            @Min(1) int quantity,
            @NotNull @DecimalMin("0.00") BigDecimal unitPrice) {
    }
}
