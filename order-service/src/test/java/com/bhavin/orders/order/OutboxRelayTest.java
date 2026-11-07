package com.bhavin.orders.order;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final KafkaEventSender sender = mock(KafkaEventSender.class);
    private final OutboxRelay relay = new OutboxRelay(outbox, sender, 50);

    private static OutboxRow row() {
        return new OutboxRow(UUID.randomUUID(), UUID.randomUUID(), "OrderCreated", "order-events", "{}");
    }

    @Test
    void publishesAndMarksEveryRow() {
        OutboxRow a = row();
        OutboxRow b = row();
        when(outbox.lockUnpublished(50)).thenReturn(List.of(a, b));

        relay.relayBatch();

        verify(sender).sendAndWait("order-events", a.aggregateId().toString(), "{}");
        verify(sender).sendAndWait("order-events", b.aggregateId().toString(), "{}");
        verify(outbox).markPublished(a.id());
        verify(outbox).markPublished(b.id());
    }

    @Test
    void stopsAtFirstFailureAndLeavesLaterRowsUnpublished() {
        OutboxRow a = row();
        OutboxRow b = row();
        OutboxRow c = row();
        when(outbox.lockUnpublished(50)).thenReturn(List.of(a, b, c));
        doThrow(new IllegalStateException("kafka down"))
                .when(sender).sendAndWait("order-events", b.aggregateId().toString(), "{}");

        relay.relayBatch();

        verify(outbox).markPublished(a.id());
        verify(outbox, never()).markPublished(b.id());
        verify(outbox, never()).markPublished(c.id());
        verify(sender, never()).sendAndWait("order-events", c.aggregateId().toString(), "{}");
    }
}
