package com.bhavin.orders.order;

public enum PublishMode {
    /** Correct: event row is written in the same transaction as the order. */
    OUTBOX,
    /** Unsafe on purpose: publish to Kafka after the DB commit. Demonstrates the dual-write problem. */
    DUAL_WRITE
}
