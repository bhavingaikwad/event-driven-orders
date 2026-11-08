package com.bhavin.orders.inventory;

import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/** Sets SIMULATED stock for many skus at once. Overwrites any existing value. */
@Component
public class StockSeeder {

    static final int MAX_SKUS = 50_000;
    static final int MAX_SKU_LENGTH = 64;

    private final DynamoDbClient dynamo;

    public StockSeeder(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    /** Validates every entry first, so a bad request changes nothing. Returns the number of skus written. */
    public int seed(Map<String, Integer> stock) {
        validate(stock);
        stock.forEach((sku, quantity) -> dynamo.putItem(PutItemRequest.builder()
                .tableName(DynamoTables.STOCK)
                .item(Map.of(
                        "sku", AttributeValue.builder().s(sku).build(),
                        "available", AttributeValue.builder().n(String.valueOf(quantity)).build()))
                .build()));
        return stock.size();
    }

    static void validate(Map<String, Integer> stock) {
        if (stock == null || stock.isEmpty()) {
            throw new IllegalArgumentException("stock map must not be empty");
        }
        if (stock.size() > MAX_SKUS) {
            throw new IllegalArgumentException("at most " + MAX_SKUS + " skus per request");
        }
        stock.forEach((sku, quantity) -> {
            if (sku == null || sku.isBlank() || sku.length() > MAX_SKU_LENGTH) {
                throw new IllegalArgumentException("sku must be 1 to " + MAX_SKU_LENGTH + " characters");
            }
            if (quantity == null || quantity < 0) {
                throw new IllegalArgumentException("quantity for a sku must be >= 0");
            }
        });
    }
}
