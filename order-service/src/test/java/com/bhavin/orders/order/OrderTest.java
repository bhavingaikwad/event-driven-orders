package com.bhavin.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static Order newOrder() {
        return Order.create("c-1", "SP", "BRL", List.of(
                new OrderItem("sku-1", 2, new BigDecimal("5.00")),
                new OrderItem("sku-2", 1, new BigDecimal("2.50"))));
    }

    @Test
    void createComputesTotalAndStartsInCreated() {
        Order order = newOrder();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("12.50");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getId()).isNotNull();
        assertThat(order.getCorrelationId()).isNotNull();
    }

    @Test
    void happyPathReachesConfirmed() {
        Order order = newOrder();
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        order.transitionTo(OrderStatus.CONFIRMED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void cancelFromCreatedIsAllowed() {
        Order order = newOrder();
        order.transitionTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void confirmedOrderCannotBeCancelled() {
        Order order = newOrder();
        order.transitionTo(OrderStatus.STOCK_RESERVED);
        order.transitionTo(OrderStatus.CONFIRMED);
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.CANCELLED))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void cannotSkipStockReservation() {
        Order order = newOrder();
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.CONFIRMED))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void terminalStatesAllowNoTransitions() {
        for (OrderStatus from : new OrderStatus[] {OrderStatus.CONFIRMED, OrderStatus.CANCELLED}) {
            for (OrderStatus to : OrderStatus.values()) {
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isFalse();
            }
        }
    }
}
