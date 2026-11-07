package com.bhavin.orders.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Standard wrapper around every event published to Kafka. */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID correlationId,
        Instant occurredAt,
        String producer,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(payload, "payload");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
    }

    public static <T> EventEnvelope<T> of(EventType type, UUID correlationId, String producer, T payload) {
        if (!type.payloadClass().isInstance(payload)) {
            throw new IllegalArgumentException(
                    "Payload " + payload.getClass().getSimpleName() + " does not match " + type.typeName());
        }
        return new EventEnvelope<>(
                UUID.randomUUID(), type.typeName(), type.schemaVersion(),
                correlationId, Instant.now(), producer, payload);
    }
}
