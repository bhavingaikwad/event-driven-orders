# INC-001: Kafka broker stopped while orders keep arriving (chaos drill)

Date: 2026-11-08. Environment: local laptop, Docker Compose. Planned failure injection, not a production incident. Stock and payment outcomes are simulated.

## Summary
Kafka was stopped for 6 min 13 s (12:47:55 to 12:54:08) while 40 orders were created through `POST /orders`. All 40 were accepted. After Kafka was started again the outbox backlog drained without restarting any service and every order finished.

## Hypothesis (written before the drill)
`POST /orders` keeps returning 201 at normal speed, unpublished outbox rows pile up, new orders wait in CREATED, and after Kafka returns everything is published and the orders complete with nothing lost.

## Timeline
- 12:47:47 baseline: 0 unpublished outbox rows, 19,808 total; 9,908 orders (6,877 CONFIRMED, 3,027 CANCELLED, 4 CREATED).
- 12:47:55 `docker stop kafka`.
- About 12:48 40 orders created with sku `chaos-1` (stock 1000).
- 12:48:38 unpublished outbox rows: 40 (total 19,848); orders in CREATED: 44.
- 12:54:08 `docker start kafka`.
- 12:54:10 unpublished: 40. 12:54:12 unpublished: 0.
- About 15 s later: CREATED 4, CONFIRMED 6,909, CANCELLED 3,035 (total 9,948).

## Measured results
- 40 of 40 requests returned HTTP 201. First request 149 ms, the other 39 between 5 and 13 ms.
- Unpublished outbox rows went 0 to 40 during the outage and back to 0 after recovery (resolution 2 s).
- Of the 40 new orders, 32 ended CONFIRMED and 8 CANCELLED (reasons not checked in this drill). None stayed in CREATED.
- Duplicate outbox events per (order, event type) after recovery: 0.

## Why it behaved this way
The order row and its outbox row are written in one Postgres transaction, so the API depends only on Postgres. A separate relay publishes rows with `published_at IS NULL` and marks them published only after Kafka accepts them, so an unavailable broker leaves rows waiting instead of losing them. The relay resumed by itself once the broker was reachable.

## Not observed or not measured
- The relay's log output during the outage was not captured.
- What inventory-service and payment-service did while the broker was down was not observed.
- Single run of a 40-order outage lasting about 6 minutes; behavior for much larger backlogs or longer outages is unknown.
- Whether a message could be published twice if the process died between the broker accepting it and the row being marked published was not tested here (consumers are idempotent, see M6).

## Follow-ups
- Alert on outbox backlog size or age; during this drill nothing would have warned an operator that rows were piling up.
- Re-run with a larger backlog to measure drain rate (relay ceiling is estimated at about 150 rows/s, not yet measured under backlog).
