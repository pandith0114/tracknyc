#!/usr/bin/env python3
"""
Generates the SYNTHETIC static GTFS fixtures in fixtures/gtfs/<system>/.

    python3 scripts/fixtures/gen_static.py            # writes fixtures/gtfs/*
    python3 scripts/fixtures/gen_static.py --check    # verifies committed files are up to date

The output is deterministic. It is a stand-in for the real MTA GTFS downloads; see
fixtures/README.md and scripts/fetch_real_data.sh for the real thing.
"""
import argparse
import csv
import io
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import network_data as nd  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(REPO, "fixtures", "gtfs")
SERVICE_ID = "SYN-Daily"


def haversine(a, b):
    r = 6371008.8
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(h)))


def hms(sec):
    return "%02d:%02d:%02d" % (sec // 3600, (sec % 3600) // 60, sec % 60)


def round30(x):
    return int(30 * round(x / 30.0))


def nyct_origin_code(sec):
    """NYCT trip ids encode the origin departure in hundredths of a minute after midnight."""
    return "%06d" % (sec * 100 // 60)


def subway_trip_ids(route, direction, origin_sec, line):
    """(static trip_id, realtime trip_id) in the NYCT style, e.g. '048000_L..N01R'."""
    core = "%s_%s..%s" % (nyct_origin_code(origin_sec), route, direction)
    static_id = "SYN26-%s-Daily-00_%s%s" % (route, core, line["path"])
    rt_id = core + line["path"] if line["rt_trip_id_has_path"] else core
    return static_id, rt_id


def run_times(stations, speed, dwell):
    """Cumulative (arrival, departure) offsets in seconds for a station list."""
    out = [(0, 0)]
    t = 0
    for i in range(1, len(stations)):
        a = (stations[i - 1][2], stations[i - 1][3])
        b = (stations[i][2], stations[i][3])
        travel = max(60, round30(haversine(a, b) / speed + 20))  # +20 s accel/brake allowance
        arr = t + travel
        dep = arr + (dwell if i < len(stations) - 1 else 0)
        out.append((arr, dep))
        t = dep
    return out


def build_subway():
    """Returns the full synthetic subway schedule as plain Python structures."""
    stops, routes, trips, stop_times, shapes = [], [], [], [], []
    seen_parent = set()
    for route, line in nd.SUBWAY_LINES.items():
        routes.append(dict(route_id=route, agency_id="MTA NYCT", route_short_name=route,
                           route_long_name=line["long_name"], route_type=1,
                           route_color=line["color"], route_text_color=line["text_color"]))
        for sid, name, lat, lon in line["stations"]:
            if sid in seen_parent:
                continue
            seen_parent.add(sid)
            stops.append(dict(stop_id=sid, stop_name=name, stop_lat=lat, stop_lon=lon,
                              location_type=1, parent_station=""))
            for d in ("N", "S"):
                stops.append(dict(stop_id=sid + d, stop_name=name, stop_lat=lat, stop_lon=lon,
                                  location_type=0, parent_station=sid))
        for direction, direction_id in (("N", 0), ("S", 1)):
            st = line["stations"] if direction == "N" else list(reversed(line["stations"]))
            shape_id = "%s..%s%s" % (route, direction, line["path"])
            for seq, (_sid, _n, lat, lon) in enumerate(st):
                shapes.append(dict(shape_id=shape_id, shape_pt_lat=lat, shape_pt_lon=lon,
                                   shape_pt_sequence=seq))
            offsets = run_times(st, nd.SUBWAY_SPEED_MPS, nd.SUBWAY_DWELL_S)
            headway = line["headway_min"] * 60
            # Offset southbound departures so the two directions don't mirror each other.
            t0 = nd.SERVICE_START_S + (0 if direction == "N" else headway // 2)
            while t0 <= nd.SERVICE_END_S:
                static_id, rt_id = subway_trip_ids(route, direction, t0, line)
                trips.append(dict(route_id=route, service_id=SERVICE_ID, trip_id=static_id,
                                  trip_headsign=st[-1][1], direction_id=direction_id, shape_id=shape_id,
                                  _rt_trip_id=rt_id, _route=route, _direction=direction, _feed=line["feed"],
                                  _origin=t0, _stations=st))
                for seq, ((sid, _n, _la, _lo), (arr, dep)) in enumerate(zip(st, offsets)):
                    stop_times.append(dict(trip_id=static_id, arrival_time=hms(t0 + arr),
                                           departure_time=hms(t0 + dep), stop_id=sid + direction,
                                           stop_sequence=seq + 1, _arr=t0 + arr, _dep=t0 + dep))
                t0 += headway
    agency = [dict(agency_id="MTA NYCT", agency_name="MTA New York City Transit (SYNTHETIC FIXTURE)",
                   agency_url="https://www.mta.info", agency_timezone="America/New_York", agency_lang="en")]
    return dict(agency=agency, routes=routes, stops=stops, trips=trips, stop_times=stop_times, shapes=shapes)


def calendar_rows():
    return [dict(service_id=SERVICE_ID, monday=1, tuesday=1, wednesday=1, thursday=1, friday=1,
                 saturday=1, sunday=1, start_date="20260101", end_date="20271231")]


COLUMNS = {
    "agency": ["agency_id", "agency_name", "agency_url", "agency_timezone", "agency_lang"],
    "routes": ["route_id", "agency_id", "route_short_name", "route_long_name", "route_type", "route_color",
               "route_text_color"],
    "stops": ["stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"],
    "trips": ["route_id", "service_id", "trip_id", "trip_headsign", "direction_id", "shape_id"],
    "stop_times": ["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"],
    "shapes": ["shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence"],
    "calendar": ["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
                 "start_date", "end_date"],
}


def render(rows, cols):
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(cols)
    for r in rows:
        w.writerow([r[c] for c in cols])
    return buf.getvalue()


def systems():
    """name -> builder. LIRR is added in milestone 3."""
    out = {"subway": build_subway}
    try:
        import network_data_lirr  # noqa: F401
        from gen_static_lirr import build_lirr
        out["lirr"] = build_lirr
    except ImportError:
        pass
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    stale = []
    for system, builder in systems().items():
        data = builder()
        data["calendar"] = calendar_rows()
        folder = os.path.join(OUT, system)
        os.makedirs(folder, exist_ok=True)
        for name, cols in COLUMNS.items():
            content = render(data[name], cols)
            path = os.path.join(folder, name + ".txt")
            if args.check:
                if not os.path.exists(path) or open(path).read() != content:
                    stale.append(path)
            else:
                with open(path, "w") as f:
                    f.write(content)
        if not args.check:
            print("%s: %d stops, %d trips, %d stop_times" % (system, len(data["stops"]), len(data["trips"]),
                                                             len(data["stop_times"])))
    if stale:
        print("out of date:", *stale, sep="\n  ")
        sys.exit(1)


if __name__ == "__main__":
    main()
