"""
SYNTHETIC LIRR GTFS-realtime recording.

The MTA railroad feeds differ from the subway feed in ways that matter to the adapter:
 - trip ids in the realtime feed match the static ones exactly (no NYCT-style re-timing), so
   the LIRR adapter uses the `exact` matching strategy;
 - there is no NyctTripDescriptor, so there is no `is_assigned` flag and no direction field;
 - stop-time updates carry a track in a different extension (1005) and the feed publishes
   `delay` on the arrival, which the decoder prefers over computing it from the schedule;
 - VehiclePosition entities DO carry real coordinates for some trains, which exercises the
   engine's GPS branch.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "gen"))
import gtfs_realtime_pb2 as rt  # noqa: E402
import mta_railroad_pb2 as mta  # noqa: E402
import gen_static_lirr  # noqa: E402
import gen_realtime  # noqa: E402

# Share the subway generator's helpers so the two recordings line up in time.
SERVICE_DATE = gen_realtime.SERVICE_DATE


def _sched():
    data = gen_static_lirr.build_lirr()
    times = {}
    for st in data["stop_times"]:
        times.setdefault(st["trip_id"], []).append(st)
    return data["trips"], times


def lirr_message(trips, times, epoch, start_local_s, step_s, k):
    t = start_local_s + k * step_s
    msg = rt.FeedMessage()
    msg.header.gtfs_realtime_version = "1.0"
    msg.header.incrementality = rt.FeedHeader.FULL_DATASET
    msg.header.timestamp = epoch(t)
    entity_no = 0
    for trip in trips:
        sts = times[trip["trip_id"]]
        d = gen_realtime.delay_at(trip, t)
        if not (trip["_origin"] - 300 <= t <= sts[-1]["_arr"] + d + 60):
            continue
        entity_no += 1
        e = msg.entity.add()
        e.id = "L%05d" % entity_no
        tu = e.trip_update
        tu.trip.trip_id = trip["trip_id"]
        tu.trip.route_id = trip["_route"]
        tu.trip.start_date = SERVICE_DATE.strftime("%Y%m%d")
        tu.timestamp = epoch(t)
        current = None
        for i, st in enumerate(sts):
            arr = st["_arr"] + d
            dep = st["_dep"] + d
            if dep < t and i < len(sts) - 1:
                continue
            stu = tu.stop_time_update.add()
            stu.stop_id = st["stop_id"]
            stu.stop_sequence = st["stop_sequence"]
            if i > 0:
                stu.arrival.time = epoch(arr)
                stu.arrival.delay = d          # railroad feeds publish the delay directly
            if i < len(sts) - 1:
                stu.departure.time = epoch(dep)
                stu.departure.delay = d
            rr = stu.Extensions[mta.mta_railroad_stop_time_update]
            rr.track = "%d" % (1 + (entity_no % 12))
            rr.trainStatus = "On Time" if d < 120 else "Delayed"
            if current is None:
                current = (i, st, arr, dep)
        if current is None or t < trip["_origin"]:
            continue
        # Half the trains report a GPS position (railroad equipment does; subway cars do not).
        if entity_no % 2 == 0:
            i, st, arr, dep = current
            prev = sts[i - 1] if i > 0 else sts[0]
            span = max(1, arr - (prev["_dep"] + d))
            frac = min(1.0, max(0.0, (t - (prev["_dep"] + d)) / span))
            a = _station(trip, prev["stop_id"])
            b = _station(trip, st["stop_id"])
            v = msg.entity.add()
            v.id = "L%05dv" % entity_no
            v.vehicle.trip.CopyFrom(tu.trip)
            v.vehicle.position.latitude = a[0] + (b[0] - a[0]) * frac
            v.vehicle.position.longitude = a[1] + (b[1] - a[1]) * frac
            v.vehicle.current_stop_sequence = st["stop_sequence"]
            v.vehicle.stop_id = st["stop_id"]
            v.vehicle.current_status = (rt.VehiclePosition.STOPPED_AT if arr <= t <= dep
                                        else rt.VehiclePosition.IN_TRANSIT_TO)
            v.vehicle.timestamp = epoch(t)
    return msg


def _station(trip, stop_id):
    for sid, _name, lat, lon in trip["_stations"]:
        if sid == stop_id:
            return (lat, lon)
    return (trip["_stations"][0][2], trip["_stations"][0][3])


def lirr_feeds(epoch, start_local_s, step_s):
    trips, times = _sched()
    return [("lirr", lambda k: lirr_message(trips, times, epoch, start_local_s, step_s, k),
             "MTA LIRR GTFS-realtime feed (lirr/gtfs-lirr)")]
