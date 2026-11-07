package com.bhavin.orders.order;

import java.util.UUID;

public class IllegalOrderTransitionException extends RuntimeException {
    public IllegalOrderTransitionException(UUID id, OrderStatus from, OrderStatus to) {
        super("Order " + id + " cannot move from " + from + " to " + to);
    }
}
