package com.bhavin.orders.payment;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

/**
 * Puts the event's correlationId into the log context while a Kafka record is handled, so every
 * log line written for that record carries it. The first correlationId field in our own event
 * envelope is read with a regex because this runs for every record and needs no second JSON parse.
 */
@Component
public class CorrelationIdInterceptor implements RecordInterceptor<Object, Object> {

    static final String KEY = "correlationId";
    private static final Pattern FIELD =
            Pattern.compile("\"correlationId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                     Consumer<Object, Object> consumer) {
        if (record.value() instanceof String json) {
            Matcher matcher = FIELD.matcher(json);
            if (matcher.find()) {
                MDC.put(KEY, matcher.group(1));
            }
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(KEY);
    }
}
