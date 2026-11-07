package com.bhavin.orders.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final String CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbClient dynamo;

    public StockService(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    /** Idempotent: the first decision for an order is stored and returned on every later call. */
    public ReservationOutcome reserve(UUID orderId, List<StockLine> requested) {
        Optional<ReservationOutcome> existing = findExisting(orderId);
        if (existing.isPresent()) {
            log.info("Order {} already decided, reusing {}", orderId, existing.get());
            return existing.get();
        }
        List<StockLine> lines = StockLine.merge(requested);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            UUID reservationId = UUID.randomUUID();
            try {
                dynamo.transactWriteItems(buildReserveRequest(orderId, reservationId, lines));
                return new ReservationOutcome.Reserved(reservationId);
            } catch (TransactionCanceledException e) {
                List<CancellationReason> reasons = e.cancellationReasons();
                if (reasons == null || reasons.isEmpty()) {
                    throw e;
                }
                if (isCode(reasons, lines.size(), CHECK_FAILED)) {
                    // Another attempt already stored a decision for this order.
                    return findExisting(orderId).orElseThrow(
                            () -> new IllegalStateException("Reservation conflict but no record for " + orderId));
                }
                if (hasTransientReason(reasons)) {
                    log.info("Transaction conflict for order {} (attempt {}/{}), retrying",
                            orderId, attempt, MAX_ATTEMPTS);
                    pause(attempt * 30L);
                    continue;
                }
                return recordRejection(orderId, rejectionReason(lines, reasons));
            }
        }
        throw new IllegalStateException(
                "Could not reserve stock for order " + orderId + " after " + MAX_ATTEMPTS + " attempts");
    }

    public Optional<Long> availableStock(String sku) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(DynamoTables.STOCK)
                .key(Map.of("sku", s(sku)))
                .consistentRead(true)
                .build()).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(item.get("available").n()));
    }

    public Optional<Map<String, String>> describeReservation(UUID orderId) {
        Map<String, AttributeValue> item = getReservationItem(orderId);
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> out = new LinkedHashMap<>();
        item.forEach((key, value) -> {
            if (value.s() != null) {
                out.put(key, value.s());
            }
        });
        return Optional.of(out);
    }

    private TransactWriteItemsRequest buildReserveRequest(UUID orderId, UUID reservationId, List<StockLine> lines) {
        List<TransactWriteItem> actions = new ArrayList<>();
        for (StockLine line : lines) {
            actions.add(TransactWriteItem.builder().update(Update.builder()
                    .tableName(DynamoTables.STOCK)
                    .key(Map.of("sku", s(line.sku())))
                    .updateExpression("SET #a = #a - :q")
                    .conditionExpression("attribute_exists(sku) AND #a >= :q")
                    .expressionAttributeNames(Map.of("#a", "available"))
                    .expressionAttributeValues(Map.of(":q", n(line.quantity())))
                    .build()).build());
        }
        Map<String, AttributeValue> item = baseReservationItem(orderId, "RESERVED");
        item.put("reservationId", s(reservationId.toString()));
        item.put("items", AttributeValue.builder().l(lines.stream()
                .map(l -> AttributeValue.builder()
                        .m(Map.of("sku", s(l.sku()), "quantity", n(l.quantity()))).build())
                .toList()).build());
        actions.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(DynamoTables.RESERVATIONS)
                .item(item)
                .conditionExpression("attribute_not_exists(orderId)")
                .build()).build());
        return TransactWriteItemsRequest.builder().transactItems(actions).build();
    }

    private ReservationOutcome recordRejection(UUID orderId, String reason) {
        Map<String, AttributeValue> item = baseReservationItem(orderId, "REJECTED");
        item.put("reason", s(reason));
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(DynamoTables.RESERVATIONS)
                    .item(item)
                    .conditionExpression("attribute_not_exists(orderId)")
                    .build());
            return new ReservationOutcome.Rejected(reason);
        } catch (ConditionalCheckFailedException e) {
            return findExisting(orderId).orElseThrow(
                    () -> new IllegalStateException("Rejection conflict but no record for " + orderId));
        }
    }

    private Optional<ReservationOutcome> findExisting(UUID orderId) {
        Map<String, AttributeValue> item = getReservationItem(orderId);
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        String status = item.get("status").s();
        return switch (status) {
            case "RESERVED", "RELEASED" -> Optional.of(
                    new ReservationOutcome.Reserved(UUID.fromString(item.get("reservationId").s())));
            case "REJECTED" -> Optional.of(new ReservationOutcome.Rejected(item.get("reason").s()));
            default -> throw new IllegalStateException("Unexpected reservation status " + status);
        };
    }

    private Map<String, AttributeValue> getReservationItem(UUID orderId) {
        return dynamo.getItem(GetItemRequest.builder()
                .tableName(DynamoTables.RESERVATIONS)
                .key(Map.of("orderId", s(orderId.toString())))
                .consistentRead(true)
                .build()).item();
    }

    private static Map<String, AttributeValue> baseReservationItem(UUID orderId, String status) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("orderId", s(orderId.toString()));
        item.put("status", s(status));
        item.put("createdAt", s(Instant.now().toString()));
        return item;
    }

    private static boolean isCode(List<CancellationReason> reasons, int index, String code) {
        return index < reasons.size() && code.equals(reasons.get(index).code());
    }

    private static boolean hasTransientReason(List<CancellationReason> reasons) {
        return reasons.stream()
                .map(CancellationReason::code)
                .anyMatch(c -> c != null && !c.equals("None") && !c.equals(CHECK_FAILED));
    }

    private static String rejectionReason(List<StockLine> lines, List<CancellationReason> reasons) {
        List<String> short_ = new ArrayList<>();
        for (int i = 0; i < lines.size() && i < reasons.size(); i++) {
            if (CHECK_FAILED.equals(reasons.get(i).code())) {
                short_.add(lines.get(i).sku());
            }
        }
        return short_.isEmpty() ? "Insufficient stock" : "Insufficient stock for: " + String.join(", ", short_);
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

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }
}
