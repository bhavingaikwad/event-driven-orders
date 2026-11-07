# PROGRESS
| Milestone | Status | Notes |
|---|---|---|
| M0 Setup | done | JDK 21 (Temurin), Maven, Docker Desktop. Compose: Kafka (KRaft), Postgres, DynamoDB Local. Jaeger/Prometheus/Grafana deferred to M11. Open: verify budget alert email; fix AWS CLI before M16. |
| M1 Repo and contracts | done | Spring Boot 4.0.8 parent, contracts module, 7 event types, envelope, versioning rule, 9 unit tests |
| M2 order-service | done | REST create/get, state machine (CREATED > STOCK_RESERVED > CONFIRMED, any non-terminal > CANCELLED), Flyway V1, optimistic-lock version column, 6 unit tests. Docker Postgres is on host port 5433 because a local Postgres already uses 5432. |
