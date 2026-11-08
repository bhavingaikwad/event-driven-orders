package com.bhavin.orders.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdInterceptorTest {

    private static final String ID = "6f1c2b9e-1d2a-4c55-9a77-0d1f3b6a8e42";

    private final CorrelationIdInterceptor interceptor = new CorrelationIdInterceptor();

    @AfterEach
    void clearLogContext() {
        MDC.clear();
    }

    private static ConsumerRecord<Object, Object> record(String json) {
        return new ConsumerRecord<>("order-events", 0, 0L, "key", json);
    }

    @Test
    void putsTheCorrelationIdInTheLogContextAndRemovesItAfterTheRecord() {
        String json = "{\"eventId\":\"x\",\"eventType\":\"OrderCreated\",\"correlationId\":\"" + ID
                + "\",\"payload\":{\"reason\":\"no correlationId here\"}}";
        ConsumerRecord<Object, Object> rec = record(json);

        interceptor.intercept(rec, null);
        assertThat(MDC.get("correlationId")).isEqualTo(ID);

        interceptor.afterRecord(rec, null);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void leavesTheLogContextAloneWhenThereIsNoCorrelationId() {
        interceptor.intercept(record("{\"hello\":\"world\"}"), null);
        assertThat(MDC.get("correlationId")).isNull();
    }
}
