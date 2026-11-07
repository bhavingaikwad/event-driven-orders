package com.bhavin.orders.contracts;

/** One topic per producing service. All records are keyed by orderId. */
public final class Topics {
    private Topics() {}

    public static final String ORDER_EVENTS = "order-events";
    public static final String INVENTORY_EVENTS = "inventory-events";
    public static final String PAYMENT_EVENTS = "payment-events";
}
