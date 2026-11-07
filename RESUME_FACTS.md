# RESUME_FACTS
Rule: only real, measured facts. Nothing estimated, nothing invented.
Stock levels and payment outcomes are SIMULATED.

## Links
- Repo: https://github.com/bhavingaikwad/event-driven-orders

## Stack (verified on my machine)
- Java 21 (Temurin 21.0.13), Maven 3.10.0, Spring Boot 4.0.8, Flyway 11.14.1, PostgreSQL 16 (Docker)

## Measured numbers
- contracts module: 7 event types, 9 unit tests, 0 failures (M1)
- order-service: 6 unit tests (order state machine), 0 failures (M2)
- Total unit tests passing so far: 15
- order-service REST verified manually against local Postgres: POST /orders 201, GET /orders/{id} 200, unknown id 404, empty items 400

## Incidents
(none yet)

## Not built / not claimed
- No automated integration tests yet (planned for M12)
