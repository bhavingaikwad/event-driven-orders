# INC-002: payment-service stopped while orders are mid-flow (chaos drill)

Date: 2026-11-08. Environment: local laptop, Docker Compose. Planned failure injection. Stock and payment outcomes are simulated.

## Summary
payment-service was stopped with Ctrl+C while 30 orders were created. The orders were accepted and reserved stock but waited in STOCK_RESERVED. After payment-service was restarted all 30 orders finished, exactly 30 payments were stored, with no duplicates and no new dead letters.

## Hypothesis (written before the drill)
Orders are still accepted, inventory still reserves stock, orders stay in STOCK_RESERVED, Kafka holds the unread events as consumer lag for the payment group, and after restart each order is charged once and all orders finish.

## Timeline
- 12:56:36 baseline: orders 3,035 CANCELLED, 6,909 CONFIRMED, 4 CREATED; payments table 8,637 rows.
- Between 12:56:36 and 12:57:29 payment-service stopped (exact time not recorded).
- 12:57:29 30 orders created with sku `chaos-2`, all HTTP 201.
- 12:57:47 orders in STOCK_RESERVED: 30. Consumer group `payment-service` had no active members; lag on `inventory-events` 30 (5+12+13) and on `order-events` 30 (13+5+12). Groups `inventory-service` and `order-service` had lag 0 on `payment-events`.
- Restart time not recorded.
- 13:01:17 first poll after restart: STOCK_RESERVED = 0 (backlog already drained).
- After: orders 3,043 CANCELLED (+8), 6,931 CONFIRMED (+22), 4 CREATED; payments 8,667 rows (+30), 8,667 distinct order IDs; `inventory-events.DLT` offset 1 before and after; no partition of the payment group with lag.

## Measured results
- 30 of 30 requests returned HTTP 201 while payment-service was down.
- Consumer lag held the backlog durably: 30 events per topic, no active consumer.
- Payments added: exactly 30, distinct order IDs 8,667 of 8,667 rows.
- Dead-letter topic `inventory-events.DLT` unchanged (offset 1 before and after).

## Deviations from the plan
- payment-service was started, stopped and started again before the polling loop began, so the restart was not clean and the drain duration was not measured.
- Whether the first start was interrupted while reading the backlog is unknown.

## Why it behaved this way
Kafka keeps events and each consumer group remembers its own position, so a stopped consumer only falls behind; it does not lose events. payment-service resumes from its committed offsets, and the database keeps one payment per order (unique constraint on `order_id`) next to idempotent consumers.

## Not observed or not measured
- Drain time and drain rate for the backlog.
- Behavior when a payment-service instance is killed abruptly in the middle of processing (Drill 3 covers the kill on order-service).
- The payment service reads `order-events` and `inventory-events` independently, so a StockReserved event can in principle be read before its OrderCreated amount is stored; that case retries (estimated at roughly 17 s of retries from the configured backoff, not measured) and then dead-letters. It did not happen in this run of 30 orders.
- Single run, 30 orders.

## Follow-ups
- Alert on consumer lag for the payment group; nothing would have warned an operator that orders were waiting.
- Re-run with a timed restart and a larger backlog to measure drain rate.
