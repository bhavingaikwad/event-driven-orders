package com.bhavin.orders.order;

import com.bhavin.orders.contracts.EventEnvelope;
import com.bhavin.orders.contracts.EventType;
import com.bhavin.orders.contracts.Topics;
import com.bhavin.orders.contracts.events.PaymentFailed;
import com.bhavin.orders.contracts.events.PaymentSucceeded;
import com.bhavin.orders.contracts.events.StockRejected;
import com.bhavin.orders.contracts.events.StockReserved;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SagaEventsListener {

    record EnvelopeHeader(String eventType) {
    }

    private final ObjectMapper mapper;
    private final OrderSagaService saga;

    public SagaEventsListener(ObjectMapper mapper, OrderSagaService saga) {
        this.mapper = mapper;
        this.saga = saga;
    }

    @KafkaListener(topics = Topics.INVENTORY_EVENTS)
    public void onInventoryEvent(String json) {
        String type = mapper.readValue(json, EnvelopeHeader.class).eventType();
        if (EventType.STOCK_RESERVED.typeName().equals(type)) {
            EventEnvelope<StockReserved> event = envelopeOf(json, StockReserved.class);
            saga.onStockReserved(event.eventId(), event.payload().orderId());
        } else if (EventType.STOCK_REJECTED.typeName().equals(type)) {
            EventEnvelope<StockRejected> event = envelopeOf(json, StockRejected.class);
            saga.onStockRejected(event.eventId(), event.payload().orderId(), event.payload().reason());
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS)
    public void onPaymentEvent(String json) {
        String type = mapper.readValue(json, EnvelopeHeader.class).eventType();
        if (EventType.PAYMENT_SUCCEEDED.typeName().equals(type)) {
            EventEnvelope<PaymentSucceeded> event = envelopeOf(json, PaymentSucceeded.class);
            saga.onPaymentSucceeded(event.eventId(), event.payload().orderId());
        } else if (EventType.PAYMENT_FAILED.typeName().equals(type)) {
            EventEnvelope<PaymentFailed> event = envelopeOf(json, PaymentFailed.class);
            saga.onPaymentFailed(event.eventId(), event.payload().orderId(), event.payload().reason());
        }
    }

    private <T> EventEnvelope<T> envelopeOf(String json, Class<T> payloadType) {
        var envelopeType = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        return mapper.readValue(json, envelopeType);
    }
}
