CREATE TABLE orders (
    id              UUID          PRIMARY KEY,
    customer_id     VARCHAR(64)   NOT NULL,
    customer_region VARCHAR(8)    NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    total_amount    NUMERIC(12,2) NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    correlation_id  UUID          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    version         BIGINT        NOT NULL
);

CREATE TABLE order_items (
    order_id   UUID          NOT NULL REFERENCES orders (id),
    sku        VARCHAR(64)   NOT NULL,
    quantity   INTEGER       NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_orders_status ON orders (status);
