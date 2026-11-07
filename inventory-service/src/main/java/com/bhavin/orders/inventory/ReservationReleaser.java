package com.bhavin.orders.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/** Compensating action of the saga: give reserved stock back, at most once per order. */
@Service
public class ReservationReleaser {

    public enum Result { RELEASED, ALREADY_RELEASED, NOTHING_TO_RELEASE }

    private static final Logger log = LoggerFactory.getLogger(ReservationReleaser.class);
    private static final int MAX_ATTEMPTS = 5;

    private final DynamoDbClient dynamo;

    public ReservationReleaser(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    public Result release(UUID orderId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Map<String, AttributeValue> reservation = dynamo.getItem(GetItemRequest.builder()
                    .tableName(DynamoTables.RESERVATIONS)
                    .key(Map.of("orderId", s(orderId.toString())))
                    .consistentRead(true)
                    .build()).item();
            if (reservation == null || reservation.isEmpty()) {
                throw new IllegalStateException("No reservation found for order " + orderId);
            }
            String status = reservation.get("status").s();
            if ("REJECTED".equals(status)) {
                return Result.NOTHING_TO_RELEASE;
            }
            if ("RELEASED".equals(status)) {
                return Result.ALREADY_RELEASED;
            }
            try {
                dynamo.transactWriteItems(buildReleaseRequest(orderId, reservation));
                return Result.RELEASED;
            } catch (TransactionCanceledException e) {
                // Another release won, or a transient conflict. Re-read the reservation and decide again.
                log.info("Release conflict for order {} (attempt {}/{}), re-checking", orderId, attempt, MAX_ATTEMPTS);
                pause(attempt * 30L);
            }
        }
        throw new IllegalStateException(
                "Could not release stock for order " + orderId + " after " + MAX_ATTEMPTS + " attempts");
    }

    private TransactWriteItemsRequest buildReleaseRequest(UUID orderId, Map<String, AttributeValue> reservation) {
        List<TransactWriteItem> actions = new ArrayList<>();
        for (AttributeValue line : reservation.get("items").l()) {
            Map<String, AttributeValue> item = line.m();
            actions.add(TransactWriteItem.builder().update(Update.builder()
                    .tableName(DynamoTables.STOCK)
                    .key(Map.of("sku", item.get("sku")))
                    .updateExpression("SET #a = #a + :q")
                    .conditionExpression("attribute_exists(sku)")
                    .expressionAttributeNames(Map.of("#a", "available"))
                    .expressionAttributeValues(Map.of(":q", item.get("quantity")))
                    .build()).build());
        }
        actions.add(TransactWriteItem.builder().update(Update.builder()
                .tableName(DynamoTables.RESERVATIONS)
                .key(Map.of("orderId", s(orderId.toString())))
                .updateExpression("SET #st = :released, releasedAt = :now")
                .conditionExpression("#st = :reserved")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(
                        ":released", s("RELEASED"),
                        ":reserved", s("RESERVED"),
                        ":now", s(Instant.now().toString())))
                .build()).build());
        return TransactWriteItemsRequest.builder().transactItems(actions).build();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying", e);
        }
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
