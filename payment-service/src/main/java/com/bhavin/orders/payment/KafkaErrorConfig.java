package com.bhavin.orders.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorConfig {

    /**
     * Interim policy: retry a failing record 20 times, 500 ms apart, then log and skip it.
     * Needed because StockReserved can arrive before OrderCreated. Replaced by backoff + DLQ in M8.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(500L, 20L));
    }
}
