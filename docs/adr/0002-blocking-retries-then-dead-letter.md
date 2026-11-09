# 0002: Blocking retries with backoff, then a dead-letter topic

Status: accepted

## Context
Consumers can fail on a message for transient reasons (a database blip) or permanent ones (a message that can never be parsed). Both must not lose the message and must not loop forever.

## Decision
All consumers share one policy: retry in place with exponential backoff (0.5 s doubling, capped at 5 s, 6 retries), then publish the record to `<topic>.DLT` with the error and origin in headers. Unparseable JSON skips the retries and goes straight to the dead-letter topic. `dlq-tool` lists dead letters and replays the pending ones to their original topic.

## Alternatives
- Non-blocking retry topics (a retry topic per delay): the partition keeps moving, but there are more topics, message ordering is lost, and the failure path is harder to reason about.
- No retries, dead-letter immediately: simple, but turns every transient blip into manual work.

## Consequences
- Blocking retries keep per-order ordering and are easy to follow, but a failing message delays everything behind it on its partition.
- Replay is safe because consumers ignore duplicates (see 0003).
- Nothing alerts on dead-letter size.
- With the configured values the retry sequence adds up to about 17.5 s (arithmetic from the configuration, not measured).

## Evidence
3 injected failures: the order still reached CONFIRMED after 4 s. 100 injected failures: 7 attempts, then the event landed in the DLT, and replay brought the order to CONFIRMED. A non-JSON message produced 2 dead letters (one per consuming service) within 5 s with no retries. During an 89 s Postgres outage, events stayed blocked in retries and all 20 finished afterward with no dead letters. Single runs on a laptop.

## Revisit when
Partition throughput matters more than simplicity, or an outage longer than the retry window needs to be survivable without dead letters.
