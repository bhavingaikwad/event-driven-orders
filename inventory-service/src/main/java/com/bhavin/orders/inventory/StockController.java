package com.bhavin.orders.inventory;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Read-only endpoints for inspecting inventory data while developing. */
@RestController
public class StockController {

    private final StockService stock;

    public StockController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/stock/{sku}")
    public ResponseEntity<Map<String, Object>> stock(@PathVariable String sku) {
        return stock.availableStock(sku)
                .map(available -> ResponseEntity.ok(Map.<String, Object>of("sku", sku, "available", available)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/reservations/{orderId}")
    public ResponseEntity<Map<String, String>> reservation(@PathVariable UUID orderId) {
        return stock.describeReservation(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
