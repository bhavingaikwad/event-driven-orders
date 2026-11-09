# 0001: Circuit breaker fails fast and cancels the order

Status: accepted

## Context
payment-service calls a payment gateway (simulated here). When the gateway is down or slow, every payment waits for a timeout, handler threads pile up, and the order pipeline backs up behind them.

## Decision
Wrap the gateway call in a Resilience4j circuit breaker. Window of the last 10 calls, at least 3 calls before it can open, opens at 50% failures or 50% slow calls (slow means over 1 s), stays open 5 s, then allows 2 half-open probe calls. Only outages and slow calls count as failures. A declined card is a normal business answer and does not count.

While the breaker is open, the payment is stored as FAILED with the reason "Payment gateway unavailable (circuit open)". The existing saga then cancels the order and releases the stock.

## Alternatives
- Park the order and resume when the gateway returns: better for the customer, but needs a stuck-order timer and a resume path that do not exist yet.
- Retry every payment with backoff: ties up consumer threads during an outage, which is what the breaker is meant to prevent.

## Consequences
- During an outage orders fail in milliseconds instead of waiting, and stock is released.
- The customer has to place the order again. Orders rejected while the breaker is open are not retried after recovery.
- The first failing order is dead-lettered (the exception propagates) before the breaker opens; later ones fail fast with a stored FAILED payment.
- Breaker state is in memory per instance and resets on restart.

## Evidence
Simulated gateway outage: breaker opened after 3 failed calls, 6 of 6 orders were cancelled with 6 rejections and no gateway calls, stock unchanged. After recovery it went OPEN, HALF_OPEN, CLOSED and 3 of 3 orders confirmed. A slow gateway (1.5 s per call, no errors) opened the breaker on slow calls alone. Single runs, simulated gateway.

## Revisit when
Customers should not have to re-order: add a park-and-resume path with a timer for stuck orders.
