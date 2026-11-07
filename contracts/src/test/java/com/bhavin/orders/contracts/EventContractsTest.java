package com.bhavin.orders.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhavin.orders.contracts.events.OrderCancelled;
import com.bhavin.orders.contracts.events.OrderConfirmed;
import com.bhavin.orders.contracts.events.OrderCreated;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventContractsTest {

    @Test
    void everyEventTypeHasUniqueNameAndValidVersion() {
        Set<String> names = new HashSet<>();
        for (EventType t : EventType.values()) {
            assertThat(names.add(t.typeName())).as("duplicate " + t.typeName()).isTrue();
            assertThat(t.schemaVersion()).isGreaterThanOrEqualTo(1);
        }
        assertThat(names).hasSize(7);
    }

    @Test
    void typeNameMatchesPayloadClassName() {
        for (EventType t : EventType.values()) {
            assertThat(t.payloadClass().getSimpleName()).isEqualTo(t.typeName());
        }
    }

    @Test
    void fromTypeNameFindsEveryType() {
        for (EventType t : EventType.values()) {
            assertThat(EventType.fromTypeName(t.typeName())).isSameAs(t);
        }
    }

    @Test
    void fromTypeNameRejectsUnknownName() {
        assertThatThrownBy(() -> EventType.fromTypeName("Nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envelopeStampsIdTimestampAndVersion() {
        UUID correlationId = UUID.randomUUID();
        OrderConfirmed payload = new OrderConfirmed(UUID.randomUUID());
        EventEnvelope<OrderConfirmed> env =
                EventEnvelope.of(EventType.ORDER_CONFIRMED, correlationId, "order-service", payload);
        assertThat(env.eventId()).isNotNull();
        assertThat(env.occurredAt()).isNotNull();
        assertThat(env.eventType()).isEqualTo("OrderConfirmed");
        assertThat(env.schemaVersion()).isEqualTo(1);
        assertThat(env.correlationId()).isEqualTo(correlationId);
        assertThat(env.payload()).isSameAs(payload);
    }

    @Test
    void envelopeRejectsPayloadOfWrongType() {
        assertThatThrownBy(() -> EventEnvelope.of(
                EventType.ORDER_CONFIRMED, UUID.randomUUID(), "order-service",
                new OrderCancelled(UUID.randomUUID(), "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envelopeRequiresCorrelationId() {
        assertThatThrownBy(() -> EventEnvelope.of(
                EventType.ORDER_CONFIRMED, null, "order-service",
                new OrderConfirmed(UUID.randomUUID())))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void orderCreatedRejectsEmptyItems() {
        assertThatThrownBy(() -> new OrderCreated(
                UUID.randomUUID(), "c1", "SP", List.of(), new BigDecimal("10.00"), "BRL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderCreatedCopiesItemsDefensively() {
        List<OrderCreated.Item> items = new ArrayList<>();
        items.add(new OrderCreated.Item("sku-1", 2, new BigDecimal("5.00")));
        OrderCreated event = new OrderCreated(
                UUID.randomUUID(), "c1", "SP", items, new BigDecimal("10.00"), "BRL");
        items.add(new OrderCreated.Item("sku-2", 1, new BigDecimal("1.00")));
        assertThat(event.items()).hasSize(1);
    }
}
