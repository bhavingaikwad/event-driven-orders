package com.bhavin.orders.order;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes outbox rows to Kafka in order. Stops at the first failure so per-order ordering is kept.
 * Delivery is at-least-once: a crash after send but before markPublished causes a re-send.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaEventSender sender;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, KafkaEventSender sender,
                       @Value("${outbox.relay.batch-size:50}") int batchSize) {
        this.outbox = outbox;
        this.sender = sender;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}")
    @Transactional
    public void relayBatch() {
        List<OutboxRow> rows = outbox.lockUnpublished(batchSize);
        int published = 0;
        for (OutboxRow row : rows) {
            try {
                sender.sendAndWait(row.topic(), row.aggregateId().toString(), row.payload());
            } catch (RuntimeException e) {
                log.warn("Relay stopped at outbox event {}: {}", row.id(), e.getMessage());
                break;
            }
            outbox.markPublished(row.id());
            published++;
        }
        if (published > 0) {
            log.info("Relayed {} outbox event(s) to Kafka", published);
        }
    }
}
