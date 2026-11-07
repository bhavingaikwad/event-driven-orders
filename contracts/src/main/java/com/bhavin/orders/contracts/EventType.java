package com.bhavin.orders.contracts;

import com.bhavin.orders.contracts.events.OrderCancelled;
import com.bhavin.orders.contracts.events.OrderConfirmed;
import com.bhavin.orders.contracts.events.OrderCreated;
import com.bhavin.orders.contracts.events.PaymentFailed;
import com.bhavin.orders.contracts.events.PaymentSucceeded;
import com.bhavin.orders.contracts.events.StockRejected;
import com.bhavin.orders.contracts.events.StockReserved;

/**
 * Registry of every event: its name, topic, current schema version and payload class.
 *
 * Versioning rule:
 * - Adding an optional field: same schemaVersion; consumers ignore unknown fields.
 * - Removing, renaming or retyping a field: breaking. Create a new payload class,
 *   bump schemaVersion, and publish both versions until consumers have migrated.
 * The typeName must equal the payload class simple name.
 */
public enum EventType {
    ORDER_CREATED("OrderCreated", Topics.ORDER_EVENTS, 1, OrderCreated.class),
    STOCK_RESERVED("StockReserved", Topics.INVENTORY_EVENTS, 1, StockReserved.class),
    STOCK_REJECTED("StockRejected", Topics.INVENTORY_EVENTS, 1, StockRejected.class),
    PAYMENT_SUCCEEDED("PaymentSucceeded", Topics.PAYMENT_EVENTS, 1, PaymentSucceeded.class),
    PAYMENT_FAILED("PaymentFailed", Topics.PAYMENT_EVENTS, 1, PaymentFailed.class),
    ORDER_CONFIRMED("OrderConfirmed", Topics.ORDER_EVENTS, 1, OrderConfirmed.class),
    ORDER_CANCELLED("OrderCancelled", Topics.ORDER_EVENTS, 1, OrderCancelled.class);

    private final String typeName;
    private final String topic;
    private final int schemaVersion;
    private final Class<?> payloadClass;

    EventType(String typeName, String topic, int schemaVersion, Class<?> payloadClass) {
        this.typeName = typeName;
        this.topic = topic;
        this.schemaVersion = schemaVersion;
        this.payloadClass = payloadClass;
    }

    public String typeName() { return typeName; }
    public String topic() { return topic; }
    public int schemaVersion() { return schemaVersion; }
    public Class<?> payloadClass() { return payloadClass; }

    public static EventType fromTypeName(String name) {
        for (EventType t : values()) {
            if (t.typeName.equals(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown event type: " + name);
    }
}
