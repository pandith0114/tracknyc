# Load tests

`push-load.js` holds N Server-Sent Events connections open and measures push lag, update
spacing, message size and sequence gaps. It needs the stack running (`docker compose up`) and
[k6](https://k6.io) installed.

```bash
# 50 viewers for two minutes against a local stack
k6 run infra/k6/push-load.js

# 500 viewers for five minutes
k6 run -e VUS=500 -e DURATION=5m infra/k6/push-load.js

# against a deployed instance
k6 run -e BASE_URL=https://trains.example.org infra/k6/push-load.js
```

The thresholds in the script fail the run if p95 push lag exceeds 7 s (the 5 s flush interval
plus 2 s of slack), if any client misses a diff, or if more than 1 % of connections fail.

While it runs, watch the Grafana dashboard (`infra/grafana/tracknyc-dashboard.json`):
`push_connected_clients` should match the VU count and `push_lag_seconds` should stay flat as
viewers are added — if it climbs with the client count, the fan-out is the bottleneck, not the
position engine.

k6 cannot keep an SSE connection open indefinitely in a scripted VU, so each iteration reads
the stream for up to 70 s and then reconnects, which also exercises the snapshot path. That
makes snapshot cost part of the measurement: with many viewers, snapshots dominate bandwidth,
which is exactly what the diff protocol is meant to avoid for long-lived connections.
