# Measuring TrackNYC

The resume bullets in the README have `[X]`, `[Y]`, `[N]`, `[Z]` placeholders on purpose. This
document says exactly how to produce each number. **Do not quote any of them until you have run
the corresponding measurement against real data** — on the synthetic fixtures every one of them
is a measurement of the fixture generator, not of the MTA.

Each section lists: what the number means, how to get it, and what a sane result looks like.

---

## 1. `[X]` — active trains tracked

> "...tracks [X] active trains..."

**What it means:** how many trains the map is drawing at a representative moment, with real
feeds. It varies enormously by time of day; quote a peak, and say so.

**How to measure.** Run in live mode for a full weekday, scrape the gauge, take the peak:

```bash
TRACKNYC_MODE=live docker compose -f infra/docker-compose.yml up -d
# ...let it run through a morning peak...
curl -s localhost:8080/actuator/prometheus | grep '^tracknyc_active_trains'
```

With Prometheus running (`docker compose --profile monitoring up`):

```promql
max_over_time(sum(tracknyc_active_trains)[24h:1m])        # peak across all systems
max_over_time(tracknyc_active_trains[24h:1m])             # per system
```

**Sanity check:** if the subway number is far below what you would expect from the number of
trains the MTA says it runs in peak hour, suspect the `is_assigned` filter (ADR 1) or trip
matching, not the map.

---

## 2. `[Y]` — update latency

> "...with [Y]-second update latency."

Be precise about which latency you mean; there are three, and they add up:

| Leg | What it is | Metric |
|---|---|---|
| Upstream freshness | how old the MTA's data already is when we fetch it | `feed_data_age_seconds` |
| Poll + compute | fetch → decode → position tick | `feed_fetch_latency_seconds` + `tracknyc.tick-interval` |
| Fan-out | position computed → bytes written to the client | `push_lag_seconds` |

```promql
# End-to-end: how old the data behind a drawn train is, p95
histogram_quantile(0.95, sum(rate(push_lag_seconds_bucket[5m])) by (le))
  + max(feed_data_age_seconds)
```

Quote the end-to-end number and break it down. With the defaults (15 s poll, 5 s tick, 5 s
flush) the self-inflicted part is bounded by roughly 25 s worst case and about 12 s typical;
whatever the upstream feed adds is outside your control, which is the honest framing.

---

## 3. `[N]` and `[Z]` — concurrent clients and p95 push lag

> "...streamed position diffs to [N] concurrent clients in load tests at p95 push lag of [Z] ms."

**How to measure.** `infra/k6/push-load.js` (see `infra/k6/README.md`):

```bash
docker compose -f infra/docker-compose.yml up -d
k6 run -e VUS=500 -e DURATION=5m infra/k6/push-load.js
```

The summary prints `push lag p50 / p95` and writes `summary.json`. `[N]` is the VU count you
can sustain **while** the thresholds pass (p95 lag < 7 s, zero sequence gaps, <1 % failed
connections); `[Z]` is `tracknyc_push_lag_ms p(95)` at that VU count.

Find the ceiling by doubling VUs until a threshold fails, then report the last passing run.
Note the hardware — "on a 4-vCPU VM" makes the number meaningful.

Watch, as you ramp up:

- `push_connected_clients` should equal the VU count. If it plateaus below, you hit
  `tracknyc.push.max-clients` or a file-descriptor limit.
- `push_lag_seconds` should stay flat. If it climbs with client count, the fan-out is the
  bottleneck: add push instances (they scale horizontally behind Redis Pub/Sub, ADR 3).
- `push_bytes_total / push_connected_clients` gives bytes per client per second — the number
  that decides what bandwidth a deployment needs.

---

## 4. Position accuracy — the number worth having

Nothing in the resume bullets covers this, and it is the most interesting measurement in the
project: **how wrong are the estimated positions?** Since there is no ground truth for
underground trains, measure the predictions the estimate is built on.

**Method: predicted vs. actual arrival.** Record the live feeds for a few hours
(`scripts/fetch_real_data.sh`), then for every (trip, stop) pair compare the arrival time
predicted *k* minutes ahead with the arrival the feed eventually reported:

```python
# scripts/analysis/prediction_error.py (write this when you have real recordings)
# For each snapshot: for each trip update: for each stop_time_update:
#     record (trip_id, stop_id, snapshot_time, predicted_arrival)
# The "actual" arrival is the last prediction before the stop drops out of the trip's
# stop_time_update list (NYCT removes passed stops).
# Then: error = predicted - actual, bucketed by how far ahead the prediction was made.
```

Report median and p90 absolute error per horizon (1, 2, 5, 10 minutes ahead). A 30 s error on a
two-minute-long segment is roughly a quarter of the distance between stations — that is the
honest translation into map terms, and the one to use in an interview.

**Second method: timetable vs. feed.** Compare each train's computed `delaySec` against the
scheduled arrival. Large systematic offsets usually mean trip matching is wrong (ADR 1), not
that the trains are late.

---

## 5. Feed reliability

Worth a line of its own, because it is what the resilience work buys:

```promql
avg_over_time(feed_status[7d])                              # per feed, 0 = up
sum(increase(feed_fetch_errors_total[7d])) by (feed)
sum(increase(feed_decode_errors_total[7d])) by (feed)
count_over_time((feed_data_age_seconds > 60)[7d:1m])        # minutes spent stale
```

"Feed X was stale for N minutes over a week and the other systems kept working" is a much
stronger claim than "it has circuit breakers", and these queries prove it.

---

## 6. Static load performance

```bash
# Timed by the loader; grep the log line it writes after each load.
docker compose -f infra/docker-compose.yml logs service | grep 'loaded static GTFS'
```

It reports stops, trips, stop_times and milliseconds. Also time the segment rebuild:

```sql
\timing on
SELECT tracknyc_rebuild_segments('subway');
```

With the real subway GTFS this is tens of thousands of segments; if it takes minutes rather
than seconds, check that `gtfs_shape` has its GiST index (`V1__gtfs_schema.sql`).
