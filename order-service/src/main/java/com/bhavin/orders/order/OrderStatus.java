package com.bhavin.orders.order;

public enum OrderStatus {
    CREATED, STOCK_RESERVED, CONFIRMED, CANCELLED;

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case CREATED -> next == STOCK_RESERVED || next == CANCELLED;
            case STOCK_RESERVED -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED, CANCELLED -> false;
        };
    }
}
