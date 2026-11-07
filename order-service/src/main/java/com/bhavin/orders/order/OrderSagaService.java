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
 * Each handler runs in one transaction that holds: the processed-event marker, the order state
 * change and the outbox row for the event it produces. A duplicate is detected by the marker
 * and ignored; out-of-order delivery is tolerated by the state checks.
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);
    private static final String PRODUCER = "order-service";
    private static final String CONSUMER = "order-service";

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ProcessedEventRepository processed;
    private final ObjectMapper mapper;

    public OrderSagaService(OrderRepository orders, OutboxRepository outbox,
                            ProcessedEventRepository processed, ObjectMapper mapper) {
        this.orders = orders;
        this.outbox = outbox;
        this.processed = processed;
        this.mapper = mapper;
    }

    @Transactional
    public void onStockReserved(UUID eventId, UUID orderId) {
        if (isDuplicate(EventType.STOCK_RESERVED, eventId, orderId)) {
            return;
        }
        Order order = load(orderId);
        if (order.getStatus() == OrderStatus.CREATED) {
            order.transitionTo(OrderStatus.STOCK_RESERVED);
            log.info("Order {} -> STOCK_RESERVED", orderId);
        } else {
            log.info("Order {} is already {}, ignoring StockReserved", orderId, order.getStatus());
        }
    }

    @Transactional
    public void onStockRejected(UUID eventId, UUID orderId, String reason) {
        if (isDuplicate(EventType.STOCK_REJECTED, eventId, orderId)) {
            return;
        }
        cancel(load(orderId), "Stock rejected: " + reason);
    }

    @Transactional
    public void onPaymentSucceeded(UUID eventId, UUID orderId) {
        if (isDuplicate(EventType.PAYMENT_SUCCEEDED, eventId, orderId)) {
            return;
        }
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
    public void onPaymentFailed(UUID eventId, UUID orderId, String reason) {
        if (isDuplicate(EventType.PAYMENT_FAILED, eventId, orderId)) {
            return;
        }
        cancel(load(orderId), "Payment failed: " + reason);
    }

    private boolean isDuplicate(EventType type, UUID eventId, UUID orderId) {
        if (processed.markProcessed(CONSUMER, type.typeName(), orderId, eventId)) {
            return false;
        }
        log.info("Duplicate {} for order {} ignored", type.typeName(), orderId);
        return true;
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
