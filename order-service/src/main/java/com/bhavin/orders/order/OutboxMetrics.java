package com.bhavin.orders.order;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Backlog gauges for the outbox. They run one cheap query each time Prometheus scrapes. */
@Component
public class OutboxMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final JdbcClient jdbc;

    public OutboxMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.pending", this, OutboxMetrics::pending)
                .description("Outbox rows not yet published to Kafka")
                .register(registry);
        Gauge.builder("outbox.oldest.pending.age", this, OutboxMetrics::oldestPendingAgeSeconds)
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox row")
                .register(registry);
    }

    double pending() {
        return query("SELECT count(*) FROM outbox_events WHERE published_at IS NULL");
    }

    double oldestPendingAgeSeconds() {
        return query("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)::double precision
                FROM outbox_events WHERE published_at IS NULL
                """);
    }

    private double query(String sql) {
        try {
            Number n = jdbc.sql(sql).query(Number.class).single();
            return n.doubleValue();
        } catch (RuntimeException e) {
            log.warn("Outbox gauge query failed: {}", e.getMessage());
            return Double.NaN;
        }
    }
}
