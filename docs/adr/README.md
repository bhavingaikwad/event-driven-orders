# Architecture decision records

These were written after the code, from the code and the measurements in `PROGRESS.md`. Each one states what was decided, what the alternatives were, what it costs, and what would make me revisit it.

| # | Decision |
|---|---|
| [0001](0001-circuit-breaker-fails-fast.md) | Circuit breaker fails fast and cancels the order |
| [0002](0002-blocking-retries-then-dead-letter.md) | Blocking retries with backoff, then a dead-letter topic |
| [0003](0003-dedupe-on-business-identity.md) | Duplicate detection keys on business identity, not event id |
| [0004](0004-no-outbox-in-inventory-and-payment.md) | No outbox or dedupe table in inventory and payment |
