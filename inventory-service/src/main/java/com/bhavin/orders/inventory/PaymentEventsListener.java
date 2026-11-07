package com.bhavin.orders.inventory;

import com.bhavin.orders.contracts.EventEnvelope;
import com.bhavin.orders.contracts.EventType;
import com.bhavin.orders.contracts.Topics;
import com.bhavin.orders.contracts.events.PaymentFailed;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);

    record EnvelopeHeader(String eventType) {
    }

    private final ObjectMapper mapper;
    private final ReservationReleaser releaser;

    public PaymentEventsListener(ObjectMapper mapper, ReservationReleaser releaser) {
        this.mapper = mapper;
        this.releaser = releaser;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS)
    public void onMessage(String json) {
        EnvelopeHeader header = mapper.readValue(json, EnvelopeHeader.class);
        if (!EventType.PAYMENT_FAILED.typeName().equals(header.eventType())) {
            return;
        }
        var type = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, PaymentFailed.class);
        EventEnvelope<PaymentFailed> envelope = mapper.readValue(json, type);
        UUID orderId = envelope.payload().orderId();
        ReservationReleaser.Result result = releaser.release(orderId);
        log.info("Order {} payment failed -> compensation: {}", orderId, result);
    }
}
