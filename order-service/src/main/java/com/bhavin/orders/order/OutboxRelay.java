package com.bhavin.orders.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Counter publishedCounter;
    private final Timer sendTimer;
    private final Timer cycleTimer;

    /** For unit tests: metrics go to a throwaway registry. */
    public OutboxRelay(OutboxRepository outbox, KafkaEventSender sender, int batchSize) {
        this(outbox, sender, batchSize, new SimpleMeterRegistry());
    }

    @Autowired
    public OutboxRelay(OutboxRepository outbox, KafkaEventSender sender,
                       @Value("${outbox.relay.batch-size:200}") int batchSize,
                       MeterRegistry registry) {
        this.outbox = outbox;
        this.sender = sender;
        this.batchSize = batchSize;
        this.publishedCounter = Counter.builder("outbox.relay.published")
                .description("Outbox rows published to Kafka").register(registry);
        this.sendTimer = Timer.builder("outbox.relay.send")
                .description("Time to send one row and receive the Kafka ack").register(registry);
        this.cycleTimer = Timer.builder("outbox.relay.cycle")
                .description("Time for one relay batch that had rows").register(registry);
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:50}")
    @Transactional
    public void relayBatch() {
        List<OutboxRow> rows = outbox.lockUnpublished(batchSize);
        if (rows.isEmpty()) {
            return;
        }
        Timer.Sample cycle = Timer.start();
        int published = 0;
        for (OutboxRow row : rows) {
            try {
                sendTimer.record(() -> sender.sendAndWait(row.topic(), row.aggregateId().toString(), row.payload()));
            } catch (RuntimeException e) {
                log.warn("Relay stopped at outbox event {}: {}", row.id(), e.getMessage());
                break;
            }
            outbox.markPublished(row.id());
            published++;
        }
        cycle.stop(cycleTimer);
        if (published > 0) {
            publishedCounter.increment(published);
            log.info("Relayed {} outbox event(s) to Kafka", published);
        }
    }
}
