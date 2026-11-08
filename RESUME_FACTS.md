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

## M8 measured facts
- Unit tests: 38 total (contracts 9, order-service 19, inventory-service 3, payment-service 7), 0 failures.
- Transient failure test: 3 injected failures in payment-service, the order still reached CONFIRMED after 4 s through retries with backoff.
- Retry exhaustion test: with 100 injected failures, payment-service made 7 attempts (counter 100 to 93), then the StockReserved event landed in inventory-events.DLT; the order stayed STOCK_RESERVED with no payment recorded.
- Replay test: dlq-tool replayed the dead letter, the order reached CONFIRMED, and order-service recorded the replayed StockReserved as a duplicate instead of reapplying it.
- Poison message test: a non-JSON message on payment-events produced 2 dead letters (one per consuming service) within 5 s, with no retries.
- Retry policy values (exponential backoff 0.5 s to 5 s, 6 retries) are configuration, not measured throughput.
- Not yet built or claimed: non-blocking retry topics, DLT monitoring/alerting, automated tests for the dead-letter path (M12).

## M9: circuit breaker (measured, gateway outcomes are SIMULATED)
- Gateway outage: the breaker opened after 3 failed gateway calls. 6 of 6 orders were then CANCELLED, 6 payments were rejected without calling the gateway (notPermittedCalls 6), and sku-2 stock was unchanged (943 before and after).
- Recovery: after the gateway came back and the 5 s open period passed, the breaker went OPEN to HALF_OPEN to CLOSED and 3 of 3 orders CONFIRMED.
- Slow gateway (1.5 s per call, 1 s threshold, 0 errors): the breaker opened on slow calls alone (slowCalls 2); 2 orders CONFIRMED and 3 were rejected fast with CANCELLED.
- A failed half-open probe sent the breaker back to OPEN (observed when the gateway was still down).
- 14 payment-service tests cover the breaker (declines are not failures, outage, slow calls, fail-fast, half-open recovery).

## M10: real-data replay (measured; stock and payment outcomes are SIMULATED)
- Data: Olist public dataset, 98,666 replayable orders. Replayed its busiest day, 2017-11-24: 1,166 orders, 919 distinct products, peak hour 87 orders.
- Open-loop replay at the original arrival times divided by a speed factor. Send lag stayed at or under 23 ms in every run.
- 2,504 orders in 4 runs (1x, 60x, 600x, 3600x): all accepted (HTTP 201), all reached CONFIRMED or CANCELLED, 0 unfinished. 1,743 CONFIRMED, 761 CANCELLED.
- Cancellations: 440 simulated payment declines (20.2% of the 2,183 payments, simulated decline rate 0.2) and 321 simulated stock rejections (stock seeded at 90% of demand).
- No new dead-letter messages during the 2,504 orders. The circuit breaker stayed CLOSED.
- Time from send to final status (polled, +/- 0.5 s), p95: 1.18 s at 1x (9 orders), 0.96 s at 60x (163 orders, 1.4 orders/s), 1.07 s at 600x (1,166 orders, 8.1 orders/s).
- At 3600x (1,166 orders in 24 s, 48.6 orders/s) the pipeline queued on a laptop: p50 5.78 s, p95 11.99 s, max 12.76 s, all orders still completed. Bottleneck not yet identified.

## M11 measured facts (one MacBook Air; stock and payment outcomes are SIMULATED)
- Replaying one real Olist day (1,166 orders) at 3600x: p95 time-to-final was 12.05 s and 12.99 s in two runs.
- Found the cause from metrics: consumer lag 0, listener threads under 1 busy, JVM CPU at most 7%, but the outbox backlog reached 674 to 766 rows and the oldest row waited 12.3 s.
- Measured the relay ceiling at 60 rows/s (6.8 ms per Kafka ack, 500 ms idle gap per 50-row batch). The model 50 / (50 x 0.0068 + 0.5) predicted 59.5.
- Changed the poll to 50 ms and the batch to 200: peak relay rate 123 to 134 rows/s, backlog at most 171 rows, oldest row at most 1.13 s, p95 time-to-final 1.04 s, 1.41 s and 1.09 s in three runs. The last run had tracing at 100% sampling, so no tracing cost was measurable.
- A saga outcome counter read 790 confirmed when only 786 orders were confirmed (it counted rolled-back transactions). After counting only after commit, saga confirmed equals payments approved (814 = 814) and the three outcomes add up to 1,166.
- One order is one trace: 15 spans across 3 services, root http post /orders.
- Observability stack cost: about 1.58 GB of images (Prometheus 336 MB, Grafana 1.08 GB, Jaeger 164 MB) and about 280 MiB of RAM.
- Caveats: single laptop, one machine sharing Kafka, Postgres, DynamoDB Local, three JVMs and the replay tool; time-to-final is accurate to about 0.5 s (polling).
