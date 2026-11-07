package com.bhavin.orders.order;

import com.bhavin.orders.contracts.EventEnvelope;
import com.bhavin.orders.contracts.EventType;
import com.bhavin.orders.contracts.events.OrderCancelled;
import com.bhavin.orders.contracts.events.OrderConfirmed;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Moves an order through the saga as inventory and payment events arrive.
 * Every handler tolerates duplicates and out-of-order delivery. Each state change and the event it
 * produces are written in one transaction (outbox), so they commit together or not at all.
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);
    private static final String PRODUCER = "order-service";

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public OrderSagaService(OrderRepository orders, OutboxRepository outbox, ObjectMapper mapper) {
        this.orders = orders;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void onStockReserved(UUID orderId) {
        Order order = load(orderId);
        if (order.getStatus() == OrderStatus.CREATED) {
            order.transitionTo(OrderStatus.STOCK_RESERVED);
            log.info("Order {} -> STOCK_RESERVED", orderId);
        } else {
            log.info("Order {} is already {}, ignoring StockReserved", orderId, order.getStatus());
        }
    }

    @Transactional
    public void onStockRejected(UUID orderId, String reason) {
        cancel(load(orderId), "Stock rejected: " + reason);
    }

    @Transactional
    public void onPaymentSucceeded(UUID orderId) {
        Order order = load(orderId);
        if (order.getStatus() == OrderStatus.CREATED) {
            // Payment is only attempted after stock was reserved, so StockReserved is just late.
            order.transitionTo(OrderStatus.STOCK_RESERVED);
        }
        if (order.getStatus() == OrderStatus.STOCK_RESERVED) {
            order.transitionTo(OrderStatus.CONFIRMED);
            publish(order, EventType.ORDER_CONFIRMED, new OrderConfirmed(order.getId()));
            log.info("Order {} -> CONFIRMED", orderId);
        } else {
            log.info("Order {} is already {}, ignoring PaymentSucceeded", orderId, order.getStatus());
        }
    }

    @Transactional
    public void onPaymentFailed(UUID orderId, String reason) {
        cancel(load(orderId), "Payment failed: " + reason);
    }

    private void cancel(Order order, String reason) {
        if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
            log.info("Order {} is already {}, ignoring cancellation", order.getId(), order.getStatus());
            return;
        }
        order.transitionTo(OrderStatus.CANCELLED);
        publish(order, EventType.ORDER_CANCELLED, new OrderCancelled(order.getId(), reason));
        log.info("Order {} -> CANCELLED ({})", order.getId(), reason);
    }

    private Order load(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private void publish(Order order, EventType type, Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.of(type, order.getCorrelationId(), PRODUCER, payload);
        outbox.insert(envelope.eventId(), order.getId(), envelope.eventType(), type.topic(),
                mapper.writeValueAsString(envelope));
    }
}
