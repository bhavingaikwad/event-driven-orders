# Runbook

For the local stack and the AWS demo box. Stock and payment outcomes are simulated. Detection today is by looking at the Grafana dashboard ("Order pipeline"); there are no alerts.

## Quick checks
- Health: `curl localhost:8081/actuator/health` (also 8082, 8083). If the database is down these can hang instead of answering (INC-004).
- Order status: `curl localhost:8081/orders/<id>`.
- Order counts by status (local): `docker exec postgres psql -U app -d orders -c "select status, count(*) from orders group by status;"`
- Outbox backlog (local): `docker exec postgres psql -U app -d orders -c "select count(*) from outbox_events where published_at is null;"`
- Metrics (names appear in Prometheus with underscores): `outbox.pending`, `outbox.oldest.pending.age`, `outbox.relay.published`, `payment.breaker.state`, saga counters `confirmed` and `cancelled`.

## Symptoms

### Orders are accepted but finish slowly
Check the outbox backlog. Under heavy load the relay is the first bottleneck: at 100 orders/s the backlog reached at least 2,710 rows and drained at about 120 rows/s. Do not restart anything. Watch the backlog fall; orders finish after it drains.

### Kafka is down
The API keeps returning 201 and orders wait as unpublished outbox rows. Restore Kafka; the relay resumes by itself (40 rows drained about 4 s after the broker returned in INC-001). Confirm the backlog returns to 0 and no event was published twice.

### payment-service or another consumer is down
Events wait in Kafka as consumer lag. Restart the service; it catches up. Check that each order has exactly one payment (INC-002).

### order-service crashed
Restart it. Orders it had accepted still finish through the outbox and the saga (INC-003). Requests sent while it was down were refused and need to be resent.

### Postgres is down
Expect HTTP 500 after about 30 s of connection-pool wait and hanging health checks. Restore Postgres. Retries continue by themselves and events finish with no dead letters in the 89 s test (INC-004). Outages longer than that were not tested.

### Dead letters appeared
List them: `mvn -q -pl dlq-tool exec:java -Dexec.args="list payment-events.DLT"` (also `order-events.DLT`, `inventory-events.DLT`). Set `KAFKA_BOOTSTRAP` if Kafka is not on `localhost:9092`.
Read the error in the headers first. Fix the cause, then replay with `exec.args="replay payment-events.DLT"`. Replay is safe because consumers ignore duplicates. Do not replay unparseable messages; they will dead-letter again.

### Orders cancelled with "Payment gateway unavailable (circuit open)"
`curl localhost:8083/circuit-breaker` shows the state. The breaker stays open 5 s, then probes. Customers must place the order again; there is no automatic retry after recovery (ADR 0001).

### Orders stuck in CREATED
Known gap: an order that never received any event stays CREATED and nothing times it out. Count them with the status query above. Check the outbox backlog first; if it is 0 the event was never published for that order.

## AWS demo teardown
`cd terraform && terraform destroy -var "my_ip_cidr=<your-ip>/32"`, then check that no instances or volumes remain in us-east-2.
