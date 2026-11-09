# 0003: Duplicate detection keys on business identity, not event id

Status: accepted

## Context
Kafka delivers at least once, so every consumer must tolerate duplicates. The obvious dedupe key is the event id.

## Decision
order-service records each processed saga step in `processed_events`, keyed by (consumer, event type, order id), in the same database transaction as the order state change and its outbox row. A second delivery of the same step is ignored and counted.

## Why not the event id
payment-service republishes its stored decision with a new event id when it sees a duplicate StockReserved. An event-id check would treat that republished event as new. The business key does not care.

## Alternatives
- Dedupe on event id: simple, but misses republished duplicates.
- Rely on state-machine checks only: works for terminal orders but not for every out-of-order case.

## Consequences
- Valid only because each saga step happens once per order. If a step could legitimately happen twice (multiple payment attempts, partial refunds), the key must gain an attempt or sequence number.
- `processed_events` has no purge job, so it grows.

## Evidence
5 duplicate PaymentFailed and 3 duplicate StockReserved events were injected for one order. The order stayed CANCELLED, stock stayed unchanged, the reservation stayed RELEASED, the outbox held exactly 2 rows and payment-service exactly 1 payment row. order-service recorded 8 duplicate PaymentFailed deliveries, and 3 of them were republished by payment-service with new event ids, which an event-id check would have missed.

## Revisit when
The saga allows a step more than once per order, or the table needs retention.
