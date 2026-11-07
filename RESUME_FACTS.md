# RESUME_FACTS
Rule: only real, measured facts. Nothing estimated, nothing invented.
Stock levels and payment outcomes are SIMULATED.

## Links
- Repo: https://github.com/bhavingaikwad/event-driven-orders

## Stack (verified on my machine)
- Java 21 (Temurin 21.0.13), Maven 3.10.0, Spring Boot 4.0.8, Flyway 11.14.1
- PostgreSQL 16 and Kafka 3.9.0 (KRaft) in Docker

## Measured numbers
- Unit tests passing: 17 (contracts 9, order-service 8), 0 failures (as of M3)
- order-service REST verified manually: POST /orders 201, GET /orders/{id} 200, unknown id 404, empty items 400
- Dual-write demo (Kafka stopped, 3 orders, single manual run on a laptop): 3 of 3 requests returned HTTP 500 (3.0 to 3.4 s each), yet all 3 orders were committed to Postgres and 0 events reached Kafka
- Outbox demo (same setup): 3 of 3 requests returned HTTP 201 (0.005 to 0.162 s each); 3 outbox rows waited unpublished, then all 3 events reached Kafka after Kafka restarted, with no manual step

## Incidents
- Real issue (M2): order-service failed to start with "role app does not exist". A Postgres on my Mac was listening on localhost:5432 ahead of Docker's. Diagnosed with lsof, fixed by moving the Docker Postgres to host port 5433.
- Injected failure (M3): stopped Kafka while creating orders; showed the dual-write loss, then fixed it with the transactional outbox.

## Not built / not claimed
- No automated integration tests yet (planned for M12)
- Delivery is at-least-once, not exactly-once; idempotent consumers come in M7

## M4 measured facts
- Unit tests: 20 total (contracts 9, order-service 8, inventory-service 3), 0 failures.
- Oversell race test: 20 simultaneous orders for a SKU with 5 simulated units, all 20 accepted by order-service (HTTP 201); final stock 0, never negative; exactly 5 StockReserved and 15 StockRejected events.
- Rejection paths verified: oversize quantity (5000) and unknown SKU both produced StockRejected, stock unchanged.
- Stock quantities are simulated seed data, not from the dataset.
- Not yet built or claimed: duplicate-event handling beyond the stored decision (M7), retries/DLQ for failed messages (M8), automated integration tests (M12). Transaction-conflict retry exists in code but its frequency was not measured.

## M5 measured facts
- Unit tests: 25 total (contracts 9, order-service 8, inventory-service 3, payment-service 5), 0 failures.
- Replay of existing Kafka history: 26 OrderCreated events stored, 9 StockReserved events produced 9 payments (8 succeeded, 1 failed); published event counts matched the table.
- Failure injection verified at runtime: rate 1.0 produced a FAILED payment, rate 0.0 a SUCCEEDED payment, an out-of-range rate returned HTTP 400.
- Mixed run: 30 orders at a simulated 30% failure rate gave 8 failed and 22 succeeded payments; database rows (41) equalled published payment events (41).
- Payment outcomes are SIMULATED; no real payment gateway is used.
- Observed gap before M6: with failed payments, inventory stayed reserved (sku-2 stock 965 after 32 orders, at least 9 units held by failed payments).
- Not yet built or claimed: stock release on payment failure (M6), formal duplicate-event tracking (M7), retries/DLQ (M8).

## M6 measured facts
- Unit tests: 34 total (contracts 9, order-service 17 incl. 9 saga tests, inventory-service 3, payment-service 5), 0 failures.
- Replaying existing Kafka history through the new consumers: of 62 orders (all CREATED before), 31 became CONFIRMED and 27 CANCELLED (31 successful payments; 10 failed payments + 17 stock rejections); 4 stayed CREATED because no event had ever been published for them. Published OrderConfirmed/OrderCancelled event counts (31/27) matched the database.
- Stock conservation checked per SKU (available = seed - units in CONFIRMED orders): sku-1 994, sku-2 974, sku-scarce 1, all matching. The 9 units of sku-2 stuck behind failed payments in M5 were released automatically.
- Live scenarios: payment failure -> order CANCELLED and reservation RELEASED, stock unchanged; payment success -> CONFIRMED; stock rejection -> CANCELLED with nothing to release. Release happened about 135 ms after the reservation in the one failure sample timed.
- Concurrency run: 30 parallel orders with a simulated 30% payment failure rate ended as 25 CONFIRMED and 5 CANCELLED, none left in a non-terminal state, stock still conserved (sku-2 945 = 1000 - 55).
- Payment outcomes and stock levels are SIMULATED.
- Not yet built or claimed: duplicate-event tracking table (M7), retry/backoff/DLQ (M8), timeouts for stuck orders, automated end-to-end tests (M12).

## M7 measured facts
- Unit tests: 36 total (contracts 9, order-service 19, inventory-service 3, payment-service 5), 0 failures.
- Duplicate-delivery test against the real stack: 5 duplicate PaymentFailed events and 3 duplicate StockReserved events injected for one order. The order stayed CANCELLED, stock stayed unchanged (945), the reservation stayed RELEASED, the outbox held exactly 2 rows and payment-service exactly 1 payment row.
- order-service recorded 8 duplicate PaymentFailed deliveries and 3 duplicate StockReserved deliveries in processed_events; 3 of the 8 PaymentFailed were republished by payment-service with new event ids, which an event-id-based dedupe would have missed.
- Total PaymentFailed events on the topic after the test: 25 (16 before, 1 for the test order, 5 injected, 3 republished).
- Not yet built or claimed: processed_events cleanup, outbox in payment-service and inventory-service, retries/backoff/DLQ (M8).
