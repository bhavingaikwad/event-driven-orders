CREATE TABLE outbox_events (
    seq          BIGSERIAL    PRIMARY KEY,
    id           UUID         NOT NULL UNIQUE,
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    topic        VARCHAR(64)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;
