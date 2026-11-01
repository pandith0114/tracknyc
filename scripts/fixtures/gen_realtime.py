#!/usr/bin/env python3
"""
Generates SYNTHETIC GTFS-realtime recordings in fixtures/realtime/<feed-id>/.

Each feed directory holds one protobuf FeedMessage per 30 s of a one-hour "recording"
(07:30-08:30 New York time on 2026-10-01), plus:
  index.csv            offset_seconds,file,header_timestamp
  manifest.properties  recording metadata read by the Java ReplayFeedAdapter

The trains follow the synthetic static schedule in fixtures/gtfs/ with deterministic,
pseudo-random delays. Some deliberately messy data is injected so the stale/vanish/backwards
rules have something to do (see MESSY_* below and fixtures/README.md).

    pip install protobuf          # any 4.x+ runtime
    python3 scripts/fixtures/gen_realtime.py
"""
import csv
import datetime as dt
import hashlib
import os
import random
import shutil
import sys
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "gen"))
import gen_static  # noqa: E402
import gtfs_realtime_pb2 as rt  # noqa: E402
import nyct_subway_pb2 as nyct  # noqa: E402

REPO = gen_static.REPO
OUT = os.path.join(REPO, "fixtures", "realtime")
TZ = ZoneInfo("America/New_York")
SERVICE_DATE = dt.date(2026, 10, 1)
START_LOCAL_S = 7 * 3600 + 30 * 60  # 07:30:00
DURATION_S = 3600
STEP_S = 30
SNAPSHOTS = DURATION_S // STEP_S

# Messy-data scenarios (snapshot indexes). Documented in fixtures/README.md.
MESSY_FREEZE = ("subway-l", range(40, 45))   # feed stops updating for 2.5 min (same payload)
MESSY_VANISH_FROM = 50                        # one southbound 7 trip disappears mid-route
MESSY_BACKWARDS = range(30, 32)               # one 1 trip's predictions jump 2 min earlier
MESSY_DUPLICATE = range(80, 86)               # one 7 trip appears twice (older duplicate)


def epoch(local_seconds):
    midnight = dt.datetime.combine(SERVICE_DATE, dt.time(0), tzinfo=TZ)
    # GTFS service-day time: noon minus 12h, which equals midnight except on DST change days.
    noon = dt.datetime.combine(SERVICE_DATE, dt.time(12), tzinfo=TZ)
    base = noon - dt.timedelta(hours=12)
    assert base == midnight
    return int(base.timestamp()) + local_seconds


def rng_for(*parts):
    h = hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest()
    return random.Random(int(h[:16], 16))


def base_delay(trip_id):
    r = rng_for("delay", trip_id).random()
    if r < 0.6:
        return 0
    if r < 0.85:
        return rng_for("d1", trip_id).randint(60, 180)
    return rng_for("d2", trip_id).randint(300, 600)


def delay_at(trip, t):
    """Delay grows linearly over the first 20 minutes of the trip, plus a little jitter."""
    if t < trip["_origin"]:
        return 0
    ramp = min(1.0, (t - trip["_origin"]) / 1200.0)
    jitter = rng_for("j", trip["trip_id"], t // 60).randint(-10, 10)
    return max(0, int(base_delay(trip["trip_id"]) * ramp) + jitter)


def abbreviate(name):
    return "".join(ch for ch in name.upper() if ch.isalnum())[:3]


class Schedule:
    def __init__(self, data):
        self.trips = data["trips"]
        self.times = {}
        for st in data["stop_times"]:
            self.times.setdefault(st["trip_id"], []).append(st)


def subway_feed_message(sched, feed_id, k, routes):
    t = START_LOCAL_S + k * STEP_S
    msg = rt.FeedMessage()
    msg.header.gtfs_realtime_version = "1.0"
    msg.header.incrementality = rt.FeedHeader.FULL_DATASET
    msg.header.timestamp = epoch(t)
    nh = msg.header.Extensions[nyct.nyct_feed_header]
    nh.nyct_subway_version = "1.0"
    for r in routes:
        p = nh.trip_replacement_period.add()
        p.route_id = r
        p.replacement_period.end = epoch(t + 1800)

    vanish_trip = None
    if feed_id == "subway-1234567":
        sb7 = [x for x in sched.trips if x["_route"] == "7" and x["_direction"] == "S"]
        vanish_trip = min(sb7, key=lambda x: abs(x["_origin"] - (7 * 3600 + 40 * 60)))["trip_id"]
    backwards_trip = None
    if feed_id == "subway-1234567":
        nb1 = [x for x in sched.trips if x["_route"] == "1" and x["_direction"] == "N"]
        backwards_trip = min(nb1, key=lambda x: abs(x["_origin"] - (7 * 3600 + 35 * 60)))["trip_id"]
    dup_trip = None
    if feed_id == "subway-1234567":
        nb7 = [x for x in sched.trips if x["_route"] == "7" and x["_direction"] == "N"]
        dup_trip = min(nb7, key=lambda x: abs(x["_origin"] - (8 * 3600 + 0 * 60)))["trip_id"]

    entity_no = 0
    for trip in sched.trips:
        if trip["_feed"] != feed_id:
            continue
        times = sched.times[trip["trip_id"]]
        d = delay_at(trip, t)
        last_arr = times[-1]["_arr"] + d
        if not (trip["_origin"] - 600 <= t <= last_arr + 30):
            continue
        if trip["trip_id"] == vanish_trip and k >= MESSY_VANISH_FROM:
            continue
        shift = -120 if (trip["trip_id"] == backwards_trip and k in MESSY_BACKWARDS) else 0
        copies = [(0, 0)]
        if trip["trip_id"] == dup_trip and k in MESSY_DUPLICATE:
            copies.append((60, 45))  # (age of the duplicate in s, its prediction offset)
        for age, extra in copies:
            entity_no += 1
            add_trip_entities(msg, trip, times, t, d + shift + extra, entity_no, age)
    return msg


def add_trip_entities(msg, trip, times, t, d, entity_no, age):
    assigned = t >= trip["_origin"] - 300
    stations = trip["_stations"]
    e = msg.entity.add()
    e.id = "%06d" % entity_no
    tu = e.trip_update
    td = tu.trip
    td.trip_id = trip["_rt_trip_id"]
    td.start_date = SERVICE_DATE.strftime("%Y%m%d")
    td.route_id = trip["_route"]
    ext = td.Extensions[nyct.nyct_trip_descriptor]
    origin = trip["_origin"]
    ext.train_id = "0%s %02d%02d+ %s/%s" % (trip["_route"], origin // 3600, (origin % 3600) // 60,
                                           abbreviate(stations[0][1]), abbreviate(stations[-1][1]))
    ext.is_assigned = assigned
    ext.direction = nyct.NyctTripDescriptor.NORTH if trip["_direction"] == "N" else nyct.NyctTripDescriptor.SOUTH
    tu.timestamp = epoch(t - age)

    current = None
    for i, st in enumerate(times):
        arr = st["_arr"] + d
        dep = st["_dep"] + d
        if dep < t and i < len(times) - 1:
            continue  # already departed: NYCT drops passed stops
        stu = tu.stop_time_update.add()
        stu.stop_id = st["stop_id"]
        if i > 0:
            stu.arrival.time = epoch(arr)
        if i < len(times) - 1:
            stu.departure.time = epoch(dep)
        track = "1" if trip["_direction"] == "N" else "2"
        sx = stu.Extensions[nyct.nyct_stop_time_update]
        sx.scheduled_track = track
        sx.actual_track = track
        if current is None:
            current = (i, st, arr, dep)
    if current is None or not assigned:
        return
    i, st, arr, dep = current
    v = msg.entity.add()
    v.id = "%06dv" % entity_no
    vp = v.vehicle
    vp.trip.CopyFrom(td)
    vp.current_stop_sequence = i + 1
    vp.stop_id = st["stop_id"]
    at_station = (i == 0 and t < dep) or (arr <= t <= dep)
    vp.current_status = (rt.VehiclePosition.STOPPED_AT if at_station else rt.VehiclePosition.IN_TRANSIT_TO)
    vp.timestamp = epoch(t - age - 5)


def feeds():
    sub = Schedule(gen_static.build_subway())
    out = [
        ("subway-1234567", lambda k: subway_feed_message(sub, "subway-1234567", k, ["1", "7"]),
         "MTA NYCT feed nyct/gtfs (lines 1-7, S); fixture contains the 1 (subset) and 7"),
        ("subway-l", lambda k: subway_feed_message(sub, "subway-l", k, ["L"]), "MTA NYCT feed nyct/gtfs-l"),
    ]
    try:
        from gen_realtime_lirr import lirr_feeds
        out.extend(lirr_feeds(epoch, START_LOCAL_S, STEP_S))
    except ImportError:
        pass
    return out


def main():
    for feed_id, build, description in feeds():
        folder = os.path.join(OUT, feed_id)
        shutil.rmtree(folder, ignore_errors=True)
        os.makedirs(folder)
        rows = []
        frozen = None
        for k in range(SNAPSHOTS):
            if MESSY_FREEZE[0] == feed_id and k in MESSY_FREEZE[1]:
                payload, ts = frozen  # upstream froze: identical payload, old header timestamp
            else:
                m = build(k)
                payload, ts = m.SerializeToString(), m.header.timestamp
                frozen = (payload, ts)
            name = "%04d.pb" % k
            with open(os.path.join(folder, name), "wb") as f:
                f.write(payload)
            rows.append((k * STEP_S, name, ts))
        with open(os.path.join(folder, "index.csv"), "w", newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["offset_seconds", "file", "header_timestamp"])
            w.writerows(rows)
        with open(os.path.join(folder, "manifest.properties"), "w") as f:
            f.write("# SYNTHETIC recording generated by scripts/fixtures/gen_realtime.py -- not real data\n")
            f.write("feed_id=%s\n" % feed_id)
            f.write("description=%s\n" % description)
            f.write("synthetic=true\n")
            f.write("recorded_start_epoch=%d\n" % epoch(START_LOCAL_S))
            f.write("loop_seconds=%d\n" % DURATION_S)
            f.write("format=gtfs-rt-protobuf\n")
        size = sum(os.path.getsize(os.path.join(folder, n)) for _, n, _ in rows)
        print("%s: %d snapshots, %.1f KB" % (feed_id, len(rows), size / 1024))


if __name__ == "__main__":
    main()
