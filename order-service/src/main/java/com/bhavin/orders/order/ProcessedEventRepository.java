package com.bhavin.orders.order;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event. True the first time. False for every later delivery of the same
     * (consumer, event type, order), and then the duplicate counter is incremented.
     * Must run inside the transaction that applies the event's effect.
     */
    public boolean markProcessed(String consumer, String eventType, UUID aggregateId, UUID eventId) {
        int inserted = jdbc.sql("""
                INSERT INTO processed_events (consumer, event_type, aggregate_id, first_event_id)
                VALUES (:consumer, :eventType, :aggregateId, :eventId)
                ON CONFLICT (consumer, event_type, aggregate_id) DO NOTHING
                """)
                .param("consumer", consumer)
                .param("eventType", eventType)
                .param("aggregateId", aggregateId)
                .param("eventId", eventId)
                .update();
        if (inserted == 1) {
            return true;
        }
        jdbc.sql("""
                UPDATE processed_events SET duplicate_count = duplicate_count + 1
                WHERE consumer = :consumer AND event_type = :eventType AND aggregate_id = :aggregateId
                """)
                .param("consumer", consumer)
                .param("eventType", eventType)
                .param("aggregateId", aggregateId)
                .update();
        return false;
    }
}
