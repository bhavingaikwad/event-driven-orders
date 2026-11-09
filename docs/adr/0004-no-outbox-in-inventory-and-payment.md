# 0004: No outbox or dedupe table in inventory and payment

Status: accepted, with a known risk

## Context
order-service uses a transactional outbox and a `processed_events` table. inventory-service and payment-service could do the same.

## Decision
They do not. They keep their own state idempotent instead: a stored decision per order, a UNIQUE `order_id` on payments, and conditional DynamoDB writes. When they see a duplicate input they republish the stored decision. They publish to Kafka directly after their own write.

## Why not a dedupe table without an outbox
If a service recorded "processed" and then failed to publish, the redelivered message would be skipped and the output event would be lost. A dedupe table is only safe when the publish is atomic with it, which needs an outbox.

## Alternatives
- Add an outbox and relay to both services: closes the gap, but means more code (and a table plus relay for DynamoDB) in two more services.

## Consequences
- A crash between a service's write and its publish is repaired by redelivery, which republishes the stored decision. This is by design and was not exercised by a crash drill on these two services.
- Downstream duplicates are absorbed by 0003.
- Republished events carry new event ids (the reason for 0003).

## Evidence
After duplicate StockReserved deliveries, payment-service had exactly 1 payment row per order. The kill -9 drill (INC-003) covered order-service only.

## Revisit when
These services need to publish multiple events atomically, or a crash drill on them shows lost or duplicated output.
