# 6. A "live-core" module inside the application repository

Date: 2026-10-28 · Status: accepted

## Context

The interesting parts of this system are not specific to trains: polling third-party feeds with
retries and circuit breakers, tracking per-feed health, keeping latest state with TTLs, fanning out
diffs to many viewers, and interpolating along geometry. A second application (live aircraft, say)
would need all of it. But publishing a shared library before a second consumer exists buys version
skew and release ceremony for nothing.

## Decision

Keep `live-core` as four Gradle modules inside this repository — `feed`, `state`, `push`, `geo` —
with the application in `tracknyc-service`. The boundary is enforced by the dependency direction:
`live-core` modules must not know what a train is. `FeedAdapter` returns bytes;
`LatestStateStore<T extends Identified>` stores anything with an id; `PushHub<T>` diffs anything
comparable by `equals`.

Spring appears only in `tracknyc-service` and in the two `live-core` classes that genuinely wrap
Spring types (`RedisLatestStateStore`, `SsePushGateway`). Everything else — the poller, the health
rules, the diff engine, the geometry — is plain Java, which is why those tests run in milliseconds
with no application context.

## Consequences

- Extraction later is a `settings.gradle.kts` change plus a publish step, not a redesign.
- The compiler keeps the abstraction honest: `live-core` has no reference to GTFS or trains.
- The cost is a slightly larger build graph than a single module would need, which is cheap.
