package com.bhavin.orders.payment;

import com.bhavin.orders.contracts.EventEnvelope;
import com.bhavin.orders.contracts.EventType;
import com.bhavin.orders.contracts.Topics;
import com.bhavin.orders.contracts.events.OrderCreated;
import com.bhavin.orders.contracts.events.PaymentFailed;
import com.bhavin.orders.contracts.events.PaymentSucceeded;
import com.bhavin.orders.contracts.events.StockReserved;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);
    private static final String PRODUCER = "payment-service";

    /** Only the event type is needed to decide whether this service cares about a message. */
    record EnvelopeHeader(String eventType) {
    }

    private final ObjectMapper mapper;
    private final PaymentRepository repository;
    private final PaymentService payments;
    private final KafkaEventSender sender;

    public PaymentEventsListener(ObjectMapper mapper, PaymentRepository repository,
                                 PaymentService payments, KafkaEventSender sender) {
        this.mapper = mapper;
        this.repository = repository;
        this.payments = payments;
        this.sender = sender;
    }

    /** Keeps a local copy of the order total, so payment never has to call order-service. */
    @KafkaListener(topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String json) {
        EnvelopeHeader header = mapper.readValue(json, EnvelopeHeader.class);
        if (!EventType.ORDER_CREATED.typeName().equals(header.eventType())) {
            return;
        }
        var type = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, OrderCreated.class);
        EventEnvelope<OrderCreated> envelope = mapper.readValue(json, type);
        OrderCreated order = envelope.payload();
        repository.saveOrderAmount(order.orderId(), order.totalAmount(), order.currency());
    }

    @KafkaListener(topics = Topics.INVENTORY_EVENTS)
    public void onInventoryEvent(String json) {
        EnvelopeHeader header = mapper.readValue(json, EnvelopeHeader.class);
        if (!EventType.STOCK_RESERVED.typeName().equals(header.eventType())) {
            return;
        }
        var type = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, StockReserved.class);
        EventEnvelope<StockReserved> envelope = mapper.readValue(json, type);

        Payment payment = payments.pay(envelope.payload().orderId());
        log.info("Order {} -> payment {} {}", payment.orderId(), payment.paymentId(), payment.status());
        publish(envelope.correlationId(), payment);
    }

    private void publish(UUID correlationId, Payment payment) {
        if (payment.status() == PaymentStatus.SUCCEEDED) {
            send(EventType.PAYMENT_SUCCEEDED, correlationId, payment.orderId(),
                    new PaymentSucceeded(payment.orderId(), payment.paymentId(),
                            payment.amount(), payment.currency()));
        } else {
            send(EventType.PAYMENT_FAILED, correlationId, payment.orderId(),
                    new PaymentFailed(payment.orderId(), payment.failureReason()));
        }
    }

    private void send(EventType type, UUID correlationId, UUID orderId, Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.of(type, correlationId, PRODUCER, payload);
        sender.sendAndWait(type.topic(), orderId.toString(), mapper.writeValueAsString(envelope));
    }
}
