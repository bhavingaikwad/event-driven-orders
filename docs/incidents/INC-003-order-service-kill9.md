# INC-003: order-service killed with kill -9 during an order burst (chaos drill)

Date: 2026-11-08. Environment: local laptop, Docker Compose. Planned failure injection. Stock and payment outcomes are simulated.

## Summary
A burst of up to 300 `POST /orders` requests was sent while the order-service JVM was killed with `kill -9` about 1 s in. 64 requests were accepted (HTTP 201) and 236 failed to connect. After restarting order-service every accepted order reached CONFIRMED or CANCELLED, the outbox backlog was fully published, and payments were stored exactly once per order.

## Hypothesis (written before the drill)
The first requests return 201 and later ones fail to connect; the database holds at least as many new orders as 201 responses; after restart every order finishes; payments increase by exactly the number of new orders with distinct order IDs, so a duplicate publish cannot cause a double charge.

## Timeline
- Baseline: orders 9,978 (6,931 CONFIRMED, 3,043 CANCELLED, 4 CREATED); 0 unpublished outbox rows; payments 8,667.
- 13:17:00 burst started (sku `chaos-3`, stock 1000); order-service Java process killed about 1 s later (13:17:00 to 13:17:01). Result: 64 x HTTP 201, 236 x connection refused.
- While order-service was down: orders 10,042 (+64) = 6,944 CONFIRMED (+13), 44 STOCK_RESERVED, 11 CREATED (+7), 3,043 CANCELLED; 23 unpublished outbox rows (13 OrderConfirmed, 10 OrderCreated); payments 8,725 (+58, payment-service kept running); consumer group `order-service` had no members and lag 45 on `payment-events` (29+3+13) plus 1 on `inventory-events`; `order-events` end offsets sum 20,009.
- Restart time not recorded.
- 13:20:12 first poll after restart: orders in CREATED or STOCK_RESERVED = 4 (only the 4 pre-existing ones; backlog already processed).
- Final: orders 10,042; 0 unpublished; 6,985 CONFIRMED (+54 vs baseline), 3,053 CANCELLED (+10), 4 CREATED; payments 8,731 with 8,731 distinct order IDs (+64); `order-events` end offsets sum 20,083 (+74).

## Measured results
- 64 of 300 requests accepted before the kill; the other 236 were refused.
- New orders in the database equal the number of 201 responses (+64): no order was committed with its response lost in this run.
- All 64 accepted orders finished: 54 CONFIRMED, 10 CANCELLED.
- Payments +64, distinct order IDs equal to rows.
- The +74 messages on `order-events` match the 23 rows that were waiting plus 51 rows created by the 51 orders that completed after the restart.

## Why it behaved this way
The order and its outbox row commit in one transaction, so anything the API acknowledged survived the crash. Unpublished rows stay in Postgres and the relay sends them after restart. Events for the saga wait in Kafka for the order-service consumer group, which resumes from its committed offsets. payment-service and inventory-service are independent and kept working while order-service was down.

## Not observed or not measured
- Whether the kill hit the window between Kafka accepting a row and the row being marked published. The counts taken cannot tell a resent row from a first send, because a resent row is already part of the waiting rows. A valid check needs the Kafka message count from before the burst.
- Drain time after restart (the polling loop started after startup had finished).
- Single run, 64 accepted orders, one kill moment.

## Follow-ups
- Re-run with a Kafka offset snapshot before the burst to measure resends, comparing new messages against twice the new order count.
- Start the polling loop before restarting to capture drain time.
- Alert on consumer lag of the order-service group.
