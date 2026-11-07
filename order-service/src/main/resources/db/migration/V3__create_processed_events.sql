-- One row per (consumer, event type, order): the business identity of a saga event.
CREATE TABLE processed_events (
    consumer        VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    first_event_id  UUID         NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    duplicate_count INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (consumer, event_type, aggregate_id)
);
