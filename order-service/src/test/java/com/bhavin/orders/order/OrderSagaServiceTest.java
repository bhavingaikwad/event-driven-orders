package com.bhavin.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bhavin.orders.contracts.EventType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrderSagaServiceTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final OrderSagaService saga = new OrderSagaService(orders, outbox, JsonMapper.builder().build());

    private Order existingOrder(OrderStatus status) {
        Order order = Order.create("c-1", "SP", "BRL",
                List.of(new OrderItem("sku-1", 2, new BigDecimal("9.90"))));
        if (status == OrderStatus.STOCK_RESERVED || status == OrderStatus.CONFIRMED) {
            order.transitionTo(OrderStatus.STOCK_RESERVED);
        }
        if (status == OrderStatus.CONFIRMED) {
            order.transitionTo(OrderStatus.CONFIRMED);
        }
        if (status == OrderStatus.CANCELLED) {
            order.transitionTo(OrderStatus.CANCELLED);
        }
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        return order;
    }

    private void verifyOutbox(Order order, EventType type, int count) {
        verify(outbox, times(count)).insert(any(UUID.class), eq(order.getId()),
                eq(type.typeName()), eq(type.topic()), anyString());
    }

    @Test
    void stockReservedMovesCreatedOrderForward() {
        Order order = existingOrder(OrderStatus.CREATED);
        saga.onStockReserved(order.getId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.STOCK_RESERVED);
        verifyNoInteractions(outbox);
    }

    @Test
    void paymentSucceededConfirmsAndWritesOutboxEvent() {
        Order order = existingOrder(OrderStatus.STOCK_RESERVED);
        saga.onPaymentSucceeded(order.getId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verifyOutbox(order, EventType.ORDER_CONFIRMED, 1);
    }

    @Test
    void paymentSucceededBeforeStockReservedStillConfirms() {
        Order order = existingOrder(OrderStatus.CREATED);
        saga.onPaymentSucceeded(order.getId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verifyOutbox(order, EventType.ORDER_CONFIRMED, 1);
    }

    @Test
    void lateStockReservedAfterConfirmationIsIgnored() {
        Order order = existingOrder(OrderStatus.CONFIRMED);
        saga.onStockReserved(order.getId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verifyNoInteractions(outbox);
    }

    @Test
    void paymentFailedCancelsAndWritesOutboxEvent() {
        Order order = existingOrder(OrderStatus.STOCK_RESERVED);
        saga.onPaymentFailed(order.getId(), "Card declined (simulated)");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verifyOutbox(order, EventType.ORDER_CANCELLED, 1);
    }

    @Test
    void stockRejectedCancelsCreatedOrder() {
        Order order = existingOrder(OrderStatus.CREATED);
        saga.onStockRejected(order.getId(), "Insufficient stock");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verifyOutbox(order, EventType.ORDER_CANCELLED, 1);
    }

    @Test
    void duplicatePaymentFailedPublishesOnlyOnce() {
        Order order = existingOrder(OrderStatus.STOCK_RESERVED);
        saga.onPaymentFailed(order.getId(), "declined");
        saga.onPaymentFailed(order.getId(), "declined");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verifyOutbox(order, EventType.ORDER_CANCELLED, 1);
    }

    @Test
    void paymentFailedAfterConfirmationIsIgnored() {
        Order order = existingOrder(OrderStatus.CONFIRMED);
        saga.onPaymentFailed(order.getId(), "declined");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verifyNoInteractions(outbox);
    }

    @Test
    void unknownOrderIsReported() {
        UUID unknown = UUID.randomUUID();
        when(orders.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> saga.onPaymentSucceeded(unknown)).isInstanceOf(OrderNotFoundException.class);
    }
}
