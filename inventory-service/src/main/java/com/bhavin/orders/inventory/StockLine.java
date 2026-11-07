package com.bhavin.orders.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StockLine(String sku, int quantity) {

    public StockLine {
        Objects.requireNonNull(sku, "sku");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
    }

    /** DynamoDB cannot touch the same item twice in one transaction, so combine repeated skus. */
    public static List<StockLine> merge(List<StockLine> lines) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (StockLine line : lines) {
            totals.merge(line.sku(), line.quantity(), Integer::sum);
        }
        return totals.entrySet().stream()
                .map(e -> new StockLine(e.getKey(), e.getValue()))
                .toList();
    }
}
