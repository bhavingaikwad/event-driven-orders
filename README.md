# Event-Driven Order Processing System

Three Spring Boot services (order, inventory, payment) coordinating a purchase through Kafka using a saga with compensating actions. All Java.

## Status
Work in progress. See PROGRESS.md.

## Run local infrastructure
    docker compose up -d

## Data
Order data will be replayed from a public e-commerce dataset (source and row counts recorded once added).
**Simulated:** stock levels and payment outcomes (configurable failure rate). They are not real data.
