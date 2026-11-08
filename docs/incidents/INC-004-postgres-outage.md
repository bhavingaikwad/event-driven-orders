# INC-004: Postgres stopped for about 89 s with saga events in flight (chaos drill)

Date: 2026-11-08. Environment: local laptop, Docker Compose. Planned failure injection. Stock and payment outcomes are simulated.

## Summary
Postgres (used by order-service and payment-service) was stopped for about 89 s while 20 saga events were in flight. API calls failed, health endpoints hung, and the Kafka consumers stayed blocked in retries. After Postgres was started again, all services recovered without a restart, every in-flight order finished, and nothing was dead-lettered.

## Hypothesis (written before the drill)
`POST /orders` fails or hangs for about 30 s; health reports a problem; in-flight events either dead-letter or are held in retries until the database returns; no data is corrupted.

## Method
inventory-service was paused with `kill -STOP`, 20 orders were created (all HTTP 201), Postgres was stopped, then inventory-service was resumed with `kill -CONT`, so its 20 `StockReserved` events reached order-service and payment-service while their database was down.

## Timeline
- Baseline: orders 10,042 (6,985 CONFIRMED, 3,053 CANCELLED, 4 CREATED); 0 unpublished outbox rows; payments 8,731; `inventory-events.DLT` offset 1.
- 13:28:27 `docker stop postgres`; inventory-service resumed right after.
- 13:28:28 `POST /orders` during the outage returned HTTP 500 after 30.06 s.
- Health probes on 8081 and 8083 returned no response within 15 s each.
- About 13:29:55 `inventory-events.DLT` still at offset 1; groups `order-service` and `payment-service` both still attached with lag 20 on `inventory-events` (7+8+5), offsets not advancing.
- 13:29:56 `docker start postgres` (outage about 89 s).
- 13:30:49 first recovery poll: orders in CREATED or STOCK_RESERVED = 4 (the old ones only).
- After: orders 10,062 (+20): 6,998 CONFIRMED (+13), 3,060 CANCELLED (+7), 4 CREATED; 0 unpublished; payments 8,751 (+20), all distinct; DLT offset 1; health 200 in 9 ms and 46 ms; new `POST` returned 201 in 40 ms.

## Measured results
- During the outage the API did not crash or corrupt data; the one request made returned 500 after 30 s and created no order.
- The in-flight events stayed blocked in retries (consumers attached, offsets stuck, nothing dead-lettered) and completed after the database returned.
- All 20 in-flight orders finished; payments +20, distinct order IDs equal to rows.

## Why it behaved this way
Both consumers block inside the listener while retrying, and each attempt waits for a database connection (a wait that matches the pool's 30 s default; not verified in the configuration). The outage ended before the retries were used up, so the events were processed normally.

## Not observed or not measured
- Behavior for an outage longer than this one; events are expected to be dead-lettered once the retries run out, but this was not tested.
- Exact recovery time (the first poll was already complete at 13:30:49, 53 s after Postgres was started).
- Service log output.
- Single run, 20 events.

## Findings and follow-ups
- Health endpoints hang while the database is down instead of reporting DOWN quickly; separate liveness and readiness and give the database check a short timeout.
- Choose the connection timeout deliberately: a long wait keeps requests and consumers blocked, a short one fails faster but reaches the dead-letter topic sooner.
- Test an outage longer than the retry window and recover with the dlq-tool.
- One extra order (`chaos-4`) was created after recovery as a probe, so later order totals include it.
