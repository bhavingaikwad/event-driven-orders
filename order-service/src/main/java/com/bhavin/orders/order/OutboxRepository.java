package com.bhavin.orders.order;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID aggregateId, String eventType, String topic, String payload) {
        jdbc.sql("""
                INSERT INTO outbox_events (id, aggregate_id, event_type, topic, payload)
                VALUES (:id, :aggregateId, :eventType, :topic, :payload)
                """)
                .param("id", id)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("topic", topic)
                .param("payload", payload)
                .update();
    }

    /** Locks the oldest unpublished rows. SKIP LOCKED lets several relay instances run side by side. */
    public List<OutboxRow> lockUnpublished(int limit) {
        return jdbc.sql("""
                SELECT id, aggregate_id, event_type, topic, payload
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY seq
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("topic"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(UUID id) {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }
}
