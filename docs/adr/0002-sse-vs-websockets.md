# 2. Server-Sent Events rather than WebSockets

Date: 2026-10-28 · Status: accepted

## Context

Thousands of viewers each need a stream of position updates every few seconds. Traffic is almost
entirely server → client: the only thing a client ever "sends" is which filters it has chosen, and
that is applied client-side anyway.

The options were polling (simple, wasteful, laggy), WebSockets (bidirectional, binary-capable) and
Server-Sent Events (one-way, HTTP, text).

## Decision

**SSE**, with a snapshot-then-diffs protocol:

- on connect the client receives a `snapshot` event with every train and the gateway's sequence
  number, then `diff` events every 5 s carrying only what changed;
- each event's `id` is the sequence number, so a client that misses one can detect the gap;
- `EventSource` reconnects on its own, and the server replies to a reconnect with a fresh snapshot,
  which is cheaper and more robust than replaying a backlog.

Milestone 1 ships plain polling of `/api/trains` (15 s) so the map works before the push layer
exists; the client swaps to the stream in milestone 2 with no change to the data model.

## Consequences

- No new protocol in the proxy chain: SSE is HTTP, so nginx, CDNs and corporate proxies handle it,
  given `proxy_buffering off` (see `infra/nginx.conf`).
- Browsers cap HTTP/1.1 connections per origin at six; over HTTP/2 this is a non-issue, and the
  deployment terminates HTTP/2.
- Text frames are larger than packed binary. Diffs plus gzip keep it small; if bandwidth ever
  dominates, the same `PushHub` can grow a binary WebSocket transport — the hub, the diff engine
  and the protocol are transport-independent on purpose.
- Clients send nothing, which removes a whole class of abuse. Load is bounded by the connection cap
  (`tracknyc.push.max-clients`), and the REST API has its own token-bucket rate limit.
