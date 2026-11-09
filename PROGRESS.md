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

## M11 observability: DONE
- Metrics: Micrometer to Prometheus (v3.13.2) to Grafana (12.4.4). The 12-panel "Order pipeline" dashboard is provisioned from the repo (observability/grafana/dashboards).
- Custom metrics: saga outcomes (counted after commit), outbox backlog and oldest-row age, relay rows/s and ack time, payment outcomes, circuit breaker state.
- Tracing: OpenTelemetry to Jaeger 2.21.0. The outbox stores the W3C traceparent (Flyway V4) and the relay restores it, so one order is one trace: 15 spans across 3 services.
- Logs: correlationId in every Kafka-handled log line (RecordInterceptor), written to logs/ (gitignored).
- Tests: 71.
- Known gaps: Prometheus scrapes appear as separate one-span traces; sampling is 100% (lower it for M14); Jaeger keeps traces in memory only; HTTP-request log lines carry traceId but not correlationId; the relay still sends row by row (measured ceiling about 150 rows/s, pipelining is the next step if M14 hits it); the relay polls the database 20 times a second even when idle; Grafana password is local-only.

## M12 - Testing (done)

What was added: Testcontainers integration tests on real Kafka, Postgres and DynamoDB Local for order-, inventory- and payment-service; a wire-format (golden JSON) contract test for all 7 events; a black-box end-to-end test gated by `E2E=true`; JaCoCo coverage reporting in the root pom.

| Module | Tests before | Tests after | Line coverage before -> after | Branch coverage before -> after |
|---|---|---|---|---|
| contracts | 9 | 25 | 72.6% -> 95.2% | 75.0% -> 75.0% |
| order-service | 27 | 32 | 45.5% -> 87.7% | 58.2% -> 80.6% |
| inventory-service | 9 | 14 | 6.2% -> 82.4% | 17.9% -> 66.3% |
| payment-service | 16 | 23 | 36.7% -> 84.8% | 52.0% -> 80.0% |
| replay-tool | 10 | 11 (1 skipped unless E2E=true) | 69.3% -> 69.3% | 49.7% -> 49.7% |
| dlq-tool | 0 | 0 | not measured | not measured |
| Total | 71 | 105 (104 run, 1 skipped) | | |

Notes:
- apache/kafka:3.9.0 under Testcontainers needs `KAFKA_LISTENERS=PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094`, otherwise the broker refuses to start (advertised.listeners cannot use 0.0.0.0).
- Mockito is loaded as a -javaagent in the order-service surefire run to avoid the dynamic self-attach warning.
- Run the end-to-end test against the running stack: `E2E=true mvn -pl replay-tool test -Dtest=EndToEndTest`.

## M13 - Chaos drills (done)

Four planned failures on the local stack, each with an incident report in `docs/incidents/`. Hypothesis written first, results measured, limits stated. Stock and payment outcomes are simulated.

| Drill | Failure | Result |
|---|---|---|
| INC-001 | Kafka stopped 6 min 13 s | 40 of 40 orders accepted, 40 outbox rows queued, drained within about 4 s of restart, no loss |
| INC-002 | payment-service stopped | 30 of 30 orders accepted, lag 30 held in Kafka, 30 payments created once after restart, no new dead letters |
| INC-003 | order-service `kill -9` mid-burst | 64 accepted, 236 refused; all 64 finished; payments +64 distinct |
| INC-004 | Postgres stopped about 89 s | API 500 after 30 s, health hung, events blocked in retries and completed after restart, no dead letters |

Gaps found: no alert on outbox backlog or consumer lag; health checks hang when the database is down; duplicate-resend window after a crash not measured; outages longer than the retry window not tested.

## M14 - Load test (done)

Open-loop k6 test (`loadtest/orders.js`, constant arrival rate), one run of 60 s per stage, on a laptop with k6 and all services on the same machine. Tracing sampling lowered to 1% (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.01`). Stock and payment outcomes are SIMULATED. "1x" = 10 orders/s is a baseline I chose, not measured real traffic. Latency below is API acceptance latency (HTTP 201 after the order and its outbox row commit), not end-to-end completion.

| Stage | Offered rate | Requests | Failed | Median | p95 | p99 | Max | Peak outbox backlog | All orders finished after load stopped |
|---|---|---|---|---|---|---|---|---|---|
| 1x | 10/s | 600 | 0 | 6.77 ms | 13.7 ms | 24.6 ms | 115.9 ms | 2 rows | by the first poll (1 s) |
| 5x | 50/s | 3,001 | 0 | 2.98 ms | 15.95 ms | 117.5 ms | 373.9 ms | 94 rows | within 2 s |
| 10x | 100/s | 6,000 | 0 | 2.76 ms | 18.4 ms | 72.9 ms | 197.7 ms | at least 2,710 rows | 19 s (outbox fully drained after 32 s) |

Reconciliation per stage: new orders equal requests, new payments equal new orders with distinct order IDs, and every order reached CONFIRMED or CANCELLED (10x: 4,838 CONFIRMED, 1,162 CANCELLED).

Findings:
- At 10x the outbox relay falls behind (backlog of at least 2,710 rows) while the API stays fast; measured drain of about 120 rows/s over the last 10 s. Consistent with the relay being the first bottleneck; per-stage timing was not measured.
- p99 latency across stages is not monotonic (24.6, 117.5, 72.9 ms); single runs, treat as noise.
- Backlog sampled every 2 s, so peaks are lower bounds.

Reproduce one stage: `k6 run --quiet -e RATE=100 -e DURATION=60s loadtest/orders.js` (seed stock first: `PUT /admin/stock {"load-1":1000000}` on inventory-service).

## M15 - CI (done)

GitHub Actions workflow `.github/workflows/ci.yml`: on push to main and on pull requests it sets up Java 21 (Temurin) with a Maven cache, runs `mvn -B -ntp verify` (the Testcontainers tests run on the Docker-enabled GitHub runner), writes a per-module line-coverage table to the run summary, and uploads surefire and JaCoCo reports as an artifact (7 days). The end-to-end test is skipped in CI because `E2E` is not set.

- Run #1 failed: invalid YAML (an unquoted colon in a step name). Fixed in the next commit.
- Run #2 passed: total 2m 27s (build job 2m 23s), artifact `test-reports` 1.05 MB, coverage table identical to the local run (contracts 95.2%, order-service 87.7%, inventory-service 82.4%, payment-service 84.8%, replay-tool 69.3%).
- No automatic deploy: deployment to AWS stays a manual step (M16).
- Notice from GitHub: `ubuntu-latest` migrates to Ubuntu 26 starting 2026-10-19; consider pinning `ubuntu-24.04`.

## M16: AWS demo deploy (Terraform) - DONE 2026-11-09
- Terraform (aws provider v6.72.0): 9 resources in us-east-2 default VPC: 1 EC2 instance, 1 security group with 4 rules, 1 IAM role, 1 policy attachment, 1 instance profile.
- First apply was rejected: this account is on the AWS Free Tier plan and `t4g.large` is not free-tier-eligible (InvalidParameterCombination). Queried `describe-instance-types` with `free-tier-eligible=true` and switched to `m7i-flex.large` (2 vCPU, 8 GiB, x86_64). AMI architecture became a variable; the Compose binary is chosen with `uname -m` on the box.
- Result: order-service health 200 at 14:08:29, about 9-10 min after launch (approximate, apply time not recorded to the second); inventory and payment health UP.
- Black-box E2E run from my laptop against the EC2 box: stock=10 orders=30 confirmed=10 cancelled=20 notFinished=0 (test wall time 7.3 s, includes internet latency, single run).
- `terraform destroy`: 9 destroyed. Afterward no Terraform state, no instances and no volumes in us-east-2. Instance ran about 11 minutes.
- Not measured: load on AWS, cost (estimated from the hourly rate, not read from billing yet), the 4 h self-terminate timer (never reached, teardown was manual), observability stack on AWS (not deployed, no tracing there).

## M17: documentation (done)
- README rewritten: scope and simulated parts first, architecture diagram, reliability table with evidence, measured results with caveats, known limitations, run/test/AWS instructions. CI badge added.
- Four ADRs in docs/adr (circuit breaker fail-fast, blocking retries then DLT, dedupe on business identity, no outbox in inventory/payment). Written after implementation and labeled as such.
- docs/runbook.md covers each drilled failure using real commands and metric names.
- Not done: cropped screenshots of Grafana, Jaeger and the CI run.
