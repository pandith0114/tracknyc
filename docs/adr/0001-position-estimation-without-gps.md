# 1. Estimating train positions without GPS

Date: 2026-10-28 · Status: accepted

## Context

The MTA subway feeds publish *predicted arrival and departure times per stop*, not coordinates:
the trains are underground, so there is no usable GPS. Some feeds (LIRR, PATH) include a
`VehiclePosition` entity, and a few of those carry real lat/lon, but most do not. A map still has
to put a dot somewhere, every few seconds, for every train.

## Decision

Estimate positions geometrically, in this order of preference:

1. **GPS** — if the feed gives a real `position` for the trip, use it as published.
2. **Interpolation** — otherwise find the stop the train last departed and the stop it is predicted
   to reach next, compute `t = (now - departed) / (dueNext - departed)`, map `t` through a motion
   profile, and place the train at that fraction of the *track geometry* between the two stops
   (`Polyline.interpolate`, mirroring PostGIS `ST_LineInterpolatePoint`).
3. **Next stop** — the milestone-1 fallback (`tracknyc.position-mode=next-stop`): draw the train at
   the station it is heading for. Honest, boring, and useful as a reference when debugging.

The geometry between each pair of consecutive stops is precomputed once per static load into a
**segment table** (`ST_LineLocatePoint` + `ST_LineSubstring` on the trip's shape), so a tick is a
hash lookup plus arithmetic rather than a geometry query per train.

Two details that matter:

- The subway feed *drops stops a train has already passed*, so the departure time of the previous
  stop is usually unavailable. We then subtract the **scheduled** run time of the segment from the
  predicted arrival. This is why the segment table stores `scheduled_run_sec`.
- Fractions are of the **geodesic** length, not of the degree-space length PostGIS uses natively on
  SRID 4326. Equal slices of time must be equal slices of *metres*, otherwise trains speed up and
  slow down on diagonal track. PostGIS interpolation therefore goes through UTM 18N
  (`PostgisSql.interpolateMetric`).

The motion profile is pluggable (`tracknyc.motion-profile`): `linear` (constant speed) by default,
`trapezoidal` (accelerate, cruise, brake) available and unit-tested.

## Consequences

- Positions are **estimates**, and the UI says so. Accuracy depends entirely on the quality of the
  upstream predictions; `docs/MEASURING.md` describes how to quantify it against actual arrivals.
- Every position is reproducible from (feed, network, clock), which makes the engine a pure
  function and the tests exact: known trip update → expected coordinates.
- When predictions jump backwards (they do), the train would jump backwards too. The tracker owns
  that rule rather than the engine, so it can be refined without touching the geometry.
