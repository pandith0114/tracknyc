# 3. Redis for latest state and fan-out

Date: 2026-10-28 · Status: accepted

## Context

One process ingests feeds and computes positions; many processes may serve viewers. Those
processes need the current state of every train, and new push instances must be able to answer a
`snapshot` immediately after starting.

## Decision

Redis 7 holds the **latest state per train** with a TTL per entity, and Redis **Pub/Sub** carries
diffs from the ingest process to every push instance.

- Entity documents live at `tracknyc:trains:e:<id>` with `SET ... PX <ttl>`; a sorted set
  `tracknyc:trains:idx` scored by expiry makes "read everything that is still alive" one `ZRANGE`
  plus one `MGET`, and expired ids are trimmed with `ZREMRANGEBYSCORE`.
- Writes are pipelined: one round trip per tick regardless of how many trains moved.
- The TTL is the trains' garbage collector. If ingest dies, state ages out instead of freezing a
  stale map in place.
- `LatestStateStore` and `MessageBus` are interfaces with in-memory implementations, so tests and
  single-process runs (`tracknyc.state.store=memory`, profile `nodb`) need no Redis at all.

Pub/Sub, not Streams: fan-out here is best-effort and the recovery path is "reconnect and get a
snapshot", so there is no reason to pay for persistence or consumer groups.

## Consequences

- Push instances scale horizontally behind a load balancer; ingest stays a single writer, which
  keeps position computation deterministic.
- A Redis outage degrades rather than breaks: the ingest process keeps computing and serving its
  own clients from memory.
- Roles are configurable (`tracknyc.state.roles=ingest,push`), so one deployment can run both and
  another can split them without code changes.
