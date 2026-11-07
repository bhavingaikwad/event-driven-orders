-- Local copy of what payment needs from OrderCreated (no synchronous call to order-service)
CREATE TABLE order_amounts (
    order_id    UUID          PRIMARY KEY,
    amount      NUMERIC(12,2) NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    received_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- One payment decision per order; UNIQUE(order_id) is what makes charging idempotent
CREATE TABLE payments (
    payment_id     UUID          PRIMARY KEY,
    order_id       UUID          NOT NULL UNIQUE,
    status         VARCHAR(16)   NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    amount         NUMERIC(12,2) NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    failure_reason TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
