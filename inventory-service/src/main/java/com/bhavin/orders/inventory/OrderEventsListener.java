package com.bhavin.orders.inventory;

import com.bhavin.orders.contracts.EventEnvelope;
import com.bhavin.orders.contracts.EventType;
import com.bhavin.orders.contracts.Topics;
import com.bhavin.orders.contracts.events.OrderCreated;
import com.bhavin.orders.contracts.events.StockRejected;
import com.bhavin.orders.contracts.events.StockReserved;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
    private static final String PRODUCER = "inventory-service";

    /** Only the event type is needed to decide whether this service cares about a message. */
    record EnvelopeHeader(String eventType) {
    }

    private final ObjectMapper mapper;
    private final StockService stock;
    private final KafkaEventSender sender;

    public OrderEventsListener(ObjectMapper mapper, StockService stock, KafkaEventSender sender) {
        this.mapper = mapper;
        this.stock = stock;
        this.sender = sender;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS)
    public void onMessage(String json) {
        EnvelopeHeader header = mapper.readValue(json, EnvelopeHeader.class);
        if (!EventType.ORDER_CREATED.typeName().equals(header.eventType())) {
            log.debug("Ignoring {} on {}", header.eventType(), Topics.ORDER_EVENTS);
            return;
        }

        var envelopeType = mapper.getTypeFactory()
                .constructParametricType(EventEnvelope.class, OrderCreated.class);
        EventEnvelope<OrderCreated> envelope = mapper.readValue(json, envelopeType);
        OrderCreated order = envelope.payload();

        List<StockLine> lines = order.items().stream()
                .map(i -> new StockLine(i.sku(), i.quantity()))
                .toList();
        ReservationOutcome outcome = stock.reserve(order.orderId(), lines);
        log.info("Order {} -> {}", order.orderId(), outcome);
        publish(envelope.correlationId(), order.orderId(), outcome);
    }

    private void publish(UUID correlationId, UUID orderId, ReservationOutcome outcome) {
        if (outcome instanceof ReservationOutcome.Reserved reserved) {
            send(EventType.STOCK_RESERVED, correlationId, orderId,
                    new StockReserved(orderId, reserved.reservationId()));
        } else if (outcome instanceof ReservationOutcome.Rejected rejected) {
            send(EventType.STOCK_REJECTED, correlationId, orderId,
                    new StockRejected(orderId, rejected.reason()));
        }
    }

    private void send(EventType type, UUID correlationId, UUID orderId, Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.of(type, correlationId, PRODUCER, payload);
        sender.sendAndWait(type.topic(), orderId.toString(), mapper.writeValueAsString(envelope));
    }
}
