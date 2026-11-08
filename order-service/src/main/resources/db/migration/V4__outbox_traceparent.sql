-- W3C traceparent of the request that wrote the row, so the relay can continue the same trace.
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(64);
