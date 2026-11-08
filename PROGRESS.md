# PROGRESS
| Milestone | Status | Notes |
|---|---|---|
| M0 Setup | done | JDK 21 (Temurin), Maven, Docker Desktop. Compose: Kafka (KRaft), Postgres, DynamoDB Local. Jaeger/Prometheus/Grafana deferred to M11. Open: verify budget alert email; fix AWS CLI before M16. |
| M1 Repo and contracts | done | Spring Boot 4.0.8 parent, contracts module, 7 event types, envelope, versioning rule, 9 unit tests |
| M2 order-service | done | REST create/get, state machine, Flyway V1, optimistic-lock version column, 6 unit tests. Docker Postgres on host port 5433 (local Postgres uses 5432). |
| M3 Transactional outbox | done | Flyway V2 outbox table, relay (SKIP LOCKED, stops at first failure), DUAL_WRITE demo mode vs OUTBOX mode, 3 topics x 3 partitions, 2 relay unit tests |

## M4: inventory-service (done)
- Consumes `OrderCreated`, reserves stock in DynamoDB Local with a conditional-write transaction, publishes `StockReserved` / `StockRejected`.
- Decision (reserved or rejected) is stored per order, so a redelivered message reuses it.
- Unit tests: 20 total (contracts 9, order-service 8, inventory-service 3).
- Stock levels are SIMULATED (seeded from config).

## M5: payment-service (done)
- Consumes `OrderCreated` (keeps a local copy of the order total) and `StockReserved`; stores one payment decision per order in PostgreSQL (UNIQUE order_id); publishes `PaymentSucceeded` / `PaymentFailed`.
- SIMULATED payment gateway: deterministic decision from orderId hash and a failure rate, changeable at runtime via PUT /simulation/failure-rate.
- Interim Kafka retry policy (20 x 500 ms, then skip) until M8.
- Known gap: stock stays reserved when payment fails (fixed by compensation in M6).

## M6: saga and compensation (done)
- order-service consumes StockReserved/StockRejected/PaymentSucceeded/PaymentFailed and moves the order to CONFIRMED or CANCELLED; the OrderConfirmed/OrderCancelled event is written through the outbox in the same transaction as the state change.
- inventory-service consumes PaymentFailed and releases the reservation (compensation) with a DynamoDB transaction conditional on status RESERVED, so a duplicate release changes nothing.
- Handlers tolerate out-of-order events (PaymentSucceeded on a CREATED order steps through STOCK_RESERVED) and ignore events for orders already in a terminal state.
- Verified manually against the real stack; automated end-to-end tests with Testcontainers are planned for M12.
- Known gap: orders that never received any event stay CREATED (no timeout handling yet).

## M7: idempotent consumers (done)
- order-service records every saga event in processed_events (key: consumer, event type, order id) in the same transaction as the order state change and its outbox row; duplicates are ignored and counted.
- Dedupe key is the business identity, not the event id, because payment-service republishes its stored decision with a new event id when it sees a duplicate StockReserved. Valid because each saga step happens once per order.
- inventory-service and payment-service keep natural idempotency (stored decision per order, UNIQUE order_id, conditional writes): a dedupe table there would be unsafe without an outbox, because they publish to Kafka directly after their own write.
- Known limitation: processed_events has no purge job yet.

## M8: retries, backoff and dead-letter topics (done)
- Every consumer (order, inventory, payment) uses a shared failure policy: blocking exponential backoff (0.5 s doubling, capped at 5 s, 6 retries), then the record is published to "<topic>.DLT" with the error and origin in headers. Unparseable JSON is not retried.
- dlq-tool (Java CLI): "list" shows dead letters with origin, consumer group and error; "replay" republishes pending ones to their original topic and remembers progress in consumer group dlq-tool.
- payment-service got simulated transient failures (PUT /simulation/transient-failures) to exercise the policy.
- Replay is safe because consumers already ignore duplicates (M6, M7).
- Known limitations: retries are blocking (a failing message delays later messages on its partition); no alerting on DLT size; dead-letter handling is verified manually, automated tests come with M12.

## M9: circuit breaker (done)
- payment-service wraps the simulated gateway in a Resilience4j 2.4.0 circuit breaker (core library, used programmatically).
- Window: last 10 calls, minimum 3 calls, opens at 50% failures or 50% slow calls (slow = over 1 s), stays open 5 s, then 2 half-open probe calls.
- Only gateway outages and slow calls count as failures. A declined card is a normal business answer.
- While open, a payment is stored as FAILED with reason "Payment gateway unavailable (circuit open)"; the saga cancels the order and releases stock.
- New simulation controls: PUT /simulation/gateway-down, PUT /simulation/gateway-latency, GET /circuit-breaker.
- Tests: 45 total (contracts 9, order 19, inventory 3, payment 14).
- Known gaps: breaker state is in memory (per instance, reset on restart); orders rejected while the breaker is open are not retried after recovery, the customer must re-order; no breaker metrics until M11.

## M10: real-data replay (done)
- New replay-tool module (plain Java, JDK HTTP client) with two commands: stats and run. It replays real Olist orders (busiest day, 2017-11-24) at their original arrival times divided by a speed factor, open loop.
- inventory-service: PUT /admin/stock bulk-sets SIMULATED stock. The tool seeds each product at 90% of the window's demand.
- Data lives in ~/Documents/DS_project/data/olist, outside the repo, and is never committed. 98,666 replayable orders (775 without items skipped, 0 malformed rows).
- Runs: 1x, 60x, 600x and 3600x (measured numbers are in RESUME_FACTS.md).
- Tests: 61 total (contracts 9, order 19, inventory 9, payment 14, replay-tool 10).
- Known gaps: PUT /admin/stock has no authentication and must be disabled before any public deploy (M16). Time to final status is measured by polling, so it is accurate to about +/- 0.5 s. Orders accumulate in the databases between runs. The bottleneck at 3600x is not identified yet (M11).
