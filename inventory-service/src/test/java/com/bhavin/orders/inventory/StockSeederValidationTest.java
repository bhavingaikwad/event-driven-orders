package com.bhavin.orders.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StockSeederValidationTest {

    @Test
    void acceptsValidStockIncludingZero() {
        assertDoesNotThrow(() -> StockSeeder.validate(Map.of("sku-a", 5, "sku-b", 0)));
    }

    @Test
    void rejectsAnEmptyMap() {
        assertThrows(IllegalArgumentException.class, () -> StockSeeder.validate(Map.of()));
    }

    @Test
    void rejectsABlankSku() {
        assertThrows(IllegalArgumentException.class, () -> StockSeeder.validate(Map.of(" ", 1)));
    }

    @Test
    void rejectsASkuLongerThan64Characters() {
        assertThrows(IllegalArgumentException.class, () -> StockSeeder.validate(Map.of("x".repeat(65), 1)));
    }

    @Test
    void rejectsANegativeQuantity() {
        assertThrows(IllegalArgumentException.class, () -> StockSeeder.validate(Map.of("sku-a", -1)));
    }

    @Test
    void rejectsAMissingQuantity() {
        Map<String, Integer> stock = new HashMap<>();
        stock.put("sku-a", null);
        assertThrows(IllegalArgumentException.class, () -> StockSeeder.validate(stock));
    }
}
