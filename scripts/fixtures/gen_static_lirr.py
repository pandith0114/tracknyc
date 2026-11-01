"""SYNTHETIC LIRR static GTFS (one branch, two stopping patterns). See network_data_lirr.py."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import network_data as nd  # noqa: E402
import network_data_lirr as lirr  # noqa: E402
from gen_static import SERVICE_ID, hms, run_times  # noqa: E402


def build_lirr():
    """Returns the synthetic LIRR schedule in the same shape as build_subway()."""
    stops, trips, stop_times, shapes = [], [], [], []
    routes = [dict(route_id=lirr.LIRR_ROUTE_ID, agency_id="LI", route_short_name="",
                   route_long_name=lirr.LIRR_ROUTE_NAME, route_type=2, route_color=lirr.LIRR_COLOR,
                   route_text_color="FFFFFF")]
    for sid, name, lat, lon in lirr.LIRR_STATIONS:
        # LIRR GTFS has no separate directional platforms, unlike the subway.
        stops.append(dict(stop_id=sid, stop_name=name, stop_lat=lat, stop_lon=lon, location_type=0,
                          parent_station=""))

    patterns = [
        ("local", list(range(len(lirr.LIRR_STATIONS))), lirr.LIRR_SPEED_MPS, lirr.LIRR_LOCAL_HEADWAY_MIN),
        ("express", lirr.LIRR_EXPRESS_STOPS, lirr.LIRR_EXPRESS_SPEED_MPS, lirr.LIRR_EXPRESS_HEADWAY_MIN),
    ]
    for direction, direction_id in (("E", 0), ("W", 1)):  # eastbound = away from Penn
        for pattern, idxs, speed, headway_min in patterns:
            stations = [lirr.LIRR_STATIONS[i] for i in idxs]
            if direction == "W":
                stations = list(reversed(stations))
            shape_id = "LIRR_%s_%s" % (pattern, direction)
            for seq, (_sid, _n, lat, lon) in enumerate(stations):
                shapes.append(dict(shape_id=shape_id, shape_pt_lat=lat, shape_pt_lon=lon, shape_pt_sequence=seq))
            offsets = run_times(stations, speed, lirr.LIRR_DWELL_S)
            headway = headway_min * 60
            t0 = nd.SERVICE_START_S + (0 if pattern == "local" else 900) + (0 if direction == "E" else 450)
            n = 0
            while t0 <= nd.SERVICE_END_S:
                # LIRR trip ids in the real feed look like "GO507_26-1234"; ours are clearly synthetic.
                trip_id = "SYN26LIRR_%s_%s_%03d" % (pattern, direction, n)
                trips.append(dict(route_id=lirr.LIRR_ROUTE_ID, service_id=SERVICE_ID, trip_id=trip_id,
                                  trip_headsign=stations[-1][1], direction_id=direction_id, shape_id=shape_id,
                                  _rt_trip_id=trip_id, _route=lirr.LIRR_ROUTE_ID, _direction=direction,
                                  _feed="lirr", _origin=t0, _stations=stations, _pattern=pattern))
                for seq, ((sid, _n, _la, _lo), (arr, dep)) in enumerate(zip(stations, offsets)):
                    stop_times.append(dict(trip_id=trip_id, arrival_time=hms(t0 + arr),
                                           departure_time=hms(t0 + dep), stop_id=sid, stop_sequence=seq + 1,
                                           _arr=t0 + arr, _dep=t0 + dep))
                t0 += headway
                n += 1
    agency = [dict(agency_id="LI", agency_name="MTA Long Island Rail Road (SYNTHETIC FIXTURE)",
                   agency_url="https://www.mta.info/lirr", agency_timezone="America/New_York", agency_lang="en")]
    return dict(agency=agency, routes=routes, stops=stops, trips=trips, stop_times=stop_times, shapes=shapes)
