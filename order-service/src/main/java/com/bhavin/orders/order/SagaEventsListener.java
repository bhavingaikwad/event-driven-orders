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
            saga.onStockReserved(payloadOf(json, StockReserved.class).orderId());
        } else if (EventType.STOCK_REJECTED.typeName().equals(type)) {
            StockRejected rejected = payloadOf(json, StockRejected.class);
            saga.onStockRejected(rejected.orderId(), rejected.reason());
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS)
    public void onPaymentEvent(String json) {
        String type = mapper.readValue(json, EnvelopeHeader.class).eventType();
        if (EventType.PAYMENT_SUCCEEDED.typeName().equals(type)) {
            saga.onPaymentSucceeded(payloadOf(json, PaymentSucceeded.class).orderId());
        } else if (EventType.PAYMENT_FAILED.typeName().equals(type)) {
            PaymentFailed failed = payloadOf(json, PaymentFailed.class);
            saga.onPaymentFailed(failed.orderId(), failed.reason());
        }
    }

    private <T> T payloadOf(String json, Class<T> payloadType) {
        var envelopeType = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        EventEnvelope<T> envelope = mapper.readValue(json, envelopeType);
        return envelope.payload();
    }
}
