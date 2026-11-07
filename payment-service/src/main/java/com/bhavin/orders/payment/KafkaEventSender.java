package com.bhavin.orders.payment;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventSender {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaEventSender(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Sends and blocks until Kafka acknowledges the record, or throws. */
    public void sendAndWait(String topic, String key, String payload) {
        try {
            kafkaTemplate.send(topic, key, payload).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing to " + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka publish to " + topic + " failed: " + e.getMessage(), e);
        }
    }
}
