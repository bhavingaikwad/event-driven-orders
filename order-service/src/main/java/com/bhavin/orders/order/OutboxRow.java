package com.bhavin.orders.order;

import java.util.UUID;

public record OutboxRow(UUID id, UUID aggregateId, String eventType, String topic, String payload) {
}
