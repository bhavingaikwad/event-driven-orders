# Event-Driven Order Processing System

Three Spring Boot services (order, inventory, payment) coordinating a purchase through Kafka using a saga with compensating actions. All Java.

## Status
Work in progress. See PROGRESS.md.

## Run local infrastructure
    docker compose up -d

## Data
Order data will be replayed from a public e-commerce dataset (source and row counts recorded once added).
**Simulated:** stock levels and payment outcomes (configurable failure rate). They are not real data.

## Event contracts
Shared in the `contracts` module as plain Java records. Every event travels inside an `EventEnvelope`
(eventId, eventType, schemaVersion, correlationId, occurredAt, producer, payload).
Topics: `order-events`, `inventory-events`, `payment-events`. Records are keyed by `orderId`.

Versioning rule:
- Adding an optional field keeps the same schemaVersion; consumers ignore unknown fields.
- Removing, renaming or retyping a field is breaking: new payload class, schemaVersion + 1,
  and the producer publishes both versions until consumers have migrated.
