package nyc.tracknyc.position;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nyc.live.core.geo.GeoMath;
import nyc.live.core.geo.LatLon;
import nyc.live.core.geo.MotionProfile;
import nyc.live.core.geo.Polyline;
import nyc.tracknyc.gtfs.GtfsTime;
import nyc.tracknyc.matching.TripMatcher;
import nyc.tracknyc.network.NetworkModel;
import nyc.tracknyc.network.Segment;
import nyc.tracknyc.network.Stop;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.network.Trip;
import nyc.tracknyc.realtime.RtFeed;

/**
 * Converts GTFS-realtime arrival predictions into train positions. Underground trains have no
 * GPS, so for each trip we find the stop it last left and the stop it is due at next, work out
 * how far through that run it is <em>in time</em>, and place it at that fraction of the track
 * geometry between the two stops (the in-memory equivalent of {@code ST_LineInterpolatePoint}).
 *
 * <p>Stateless and pure: the same feed, network and clock always give the same answer, which
 * keeps the unit tests ("known trip update → expected coordinates") simple.
 */
public final class PositionEngine {

    /** Speed used to estimate a run time when neither feed nor schedule provides one. */
    static final double FALLBACK_SPEED_MPS = 10.0;

    private final PositionMode mode;
    private final MotionProfile motion;

    public PositionEngine(PositionMode mode, MotionProfile motion) {
        this.mode = mode;
        this.motion = motion;
    }

    /**
     * @param feedNow     "now" in the feed's clock
     * @param clockOffset feed clock minus wall clock (zero for live feeds); output times are wall-clock
     */
    public List<TrainState> compute(SystemNetwork net, TripMatcher matcher, RtFeed feed, Instant feedNow,
                                    Duration clockOffset) {
        Map<String, RtFeed.TripUpdate> unique = dedupe(feed);
        List<TrainState> out = new ArrayList<>(unique.size());
        for (RtFeed.TripUpdate tu : unique.values()) {
            estimate(net, matcher, feed, tu, feedNow, clockOffset).ifPresent(out::add);
        }
        return out;
    }

    /** Duplicate trips (same trip id in two entities) happen; keep the most recently updated one. */
    static Map<String, RtFeed.TripUpdate> dedupe(RtFeed feed) {
        Map<String, RtFeed.TripUpdate> byTrip = new LinkedHashMap<>();
        for (RtFeed.TripUpdate tu : feed.tripUpdates()) {
            if (tu.tripId() == null || tu.tripId().isEmpty()) {
                continue;
            }
            RtFeed.TripUpdate existing = byTrip.get(tu.tripId());
            if (existing == null || timestampOf(tu, feed).isAfter(timestampOf(existing, feed))) {
                byTrip.put(tu.tripId(), tu);
            }
        }
        return byTrip;
    }

    private static Instant timestampOf(RtFeed.TripUpdate tu, RtFeed feed) {
        return tu.timestamp() != null ? tu.timestamp() : feed.headerTimestamp();
    }

    Optional<TrainState> estimate(SystemNetwork net, TripMatcher matcher, RtFeed feed, RtFeed.TripUpdate tu,
                                  Instant now, Duration offset) {
        if (tu.relationship() == RtFeed.TripRelationship.CANCELED) {
            return Optional.empty();
        }
        if (tu.nyct() != null && !tu.nyct().assigned()) {
            return Optional.empty(); // NYCT: no physical train assigned yet — nothing to draw
        }
        List<RtFeed.StopTimeUpdate> stus = new ArrayList<>();
        for (RtFeed.StopTimeUpdate s : tu.stopTimeUpdates()) {
            if (!s.skipped() && s.arrivalOrDeparture() != null && net.stop(s.stopId()).isPresent()) {
                stus.add(s);
            }
        }
        if (stus.isEmpty()) {
            return Optional.empty();
        }
        LocalDate serviceDate = tu.startDate() != null ? tu.startDate() : now.atZone(net.zone()).toLocalDate();
        Optional<Trip> trip = matcher.match(tu, serviceDate);
        String routeId = tu.routeId() != null ? tu.routeId() : trip.map(Trip::routeId).orElse(null);

        // The next stop is the first one the train has not departed yet.
        int idx = -1;
        for (int i = 0; i < stus.size(); i++) {
            if (stus.get(i).departureOrArrival().isAfter(now)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return Optional.empty(); // every predicted stop is in the past: trip finished
        }
        RtFeed.StopTimeUpdate next = stus.get(idx);
        Instant nextArr = next.arrivalOrDeparture();
        Stop nextStop = net.stop(next.stopId()).orElseThrow();
        Stop nextStation = net.stationOf(next.stopId()).orElse(nextStop);
        Optional<RtFeed.Vehicle> vehicle = feed.vehicleForTrip(tu.tripId());

        // Previous stop and when the train left it.
        String prevStopId = null;
        Instant prevDep = null;
        if (idx > 0) {
            prevStopId = stus.get(idx - 1).stopId();
            prevDep = stus.get(idx - 1).departureOrArrival();
        } else {
            prevStopId = previousStopFromSchedule(net, trip, routeId, next.stopId());
        }
        Optional<Segment> segment = prevStopId == null ? Optional.empty() : net.segment(prevStopId, next.stopId());
        if (prevStopId != null && prevDep == null) {
            int runSec = runTimeSeconds(net, trip, prevStopId, next.stopId(), segment);
            prevDep = nextArr.minusSeconds(runSec);
        }

        String status;
        String source;
        LatLon pos;
        double bearing = 0;
        double speed = 0;
        double progress = 0;
        boolean dwelling = !nextArr.isAfter(now)
                || (vehicle.isPresent() && vehicle.get().status() == RtFeed.VehicleStatus.STOPPED_AT
                && next.stopId().equals(vehicle.get().stopId()) && idx == 0 && prevStopId == null);
        if (vehicle.isPresent() && vehicle.get().position() != null) {
            status = dwelling ? "AT_STATION" : "IN_TRANSIT";
            source = "GPS";
            pos = vehicle.get().position();
            bearing = vehicle.get().bearing() == null ? 0 : vehicle.get().bearing();
        } else if (dwelling) {
            status = "AT_STATION";
            source = mode == PositionMode.NEXT_STOP ? "NEXT_STOP" : "INTERPOLATED";
            pos = nextStation.location();
            progress = 1;
            bearing = segment.map(s -> s.geometry().bearingAt(1)).orElse(0.0);
        } else if (prevStopId == null || prevDep == null || prevDep.isAfter(now)) {
            // Not yet left the origin terminal (or no idea where it came from).
            status = prevStopId == null ? "WAITING_AT_ORIGIN" : "AT_STATION";
            source = mode == PositionMode.NEXT_STOP ? "NEXT_STOP" : "INTERPOLATED";
            Stop at = prevStopId == null ? nextStation
                    : net.stationOf(prevStopId).orElse(nextStation);
            pos = at.location();
            bearing = segment.map(s -> s.geometry().bearingAt(0)).orElse(0.0);
        } else if (mode == PositionMode.NEXT_STOP) {
            status = "IN_TRANSIT";
            source = "NEXT_STOP";
            pos = nextStation.location();
            bearing = segment.map(s -> s.geometry().bearingAt(1)).orElse(0.0);
        } else {
            status = "IN_TRANSIT";
            source = "INTERPOLATED";
            Stop prev = net.stop(prevStopId).orElse(nextStop);
            Polyline geom = segment.map(Segment::geometry)
                    .orElseGet(() -> Polyline.straight(prev.location(), nextStop.location()));
            double total = Math.max(1, Duration.between(prevDep, nextArr).toMillis() / 1000.0);
            double timeFraction = GeoMath.clamp(Duration.between(prevDep, now).toMillis() / 1000.0 / total, 0, 1);
            progress = motion.distanceFraction(timeFraction);
            pos = geom.interpolate(progress);
            bearing = geom.bearingAt(progress);
            speed = geom.lengthMeters() / total;
        }

        Integer delay = delaySeconds(net, trip, next, serviceDate);
        String headsign = trip.map(Trip::headsign).filter(h -> !h.isBlank())
                .orElseGet(() -> net.stationOf(stus.get(stus.size() - 1).stopId()).map(Stop::name).orElse(null));
        String direction = tu.nyct() != null && tu.nyct().direction() != null ? tu.nyct().direction()
                : trip.map(t -> Integer.toString(t.directionId())).orElse(null);
        Instant data = tu.timestamp() != null ? tu.timestamp() : feed.headerTimestamp();
        LatLon p = pos.rounded(6);
        String sys = net.system();
        return Optional.of(new TrainState(
                NetworkModel.publicId(sys, tu.tripId()), sys, feed.feedId(), routeId, tu.tripId(),
                tu.nyct() == null ? null : tu.nyct().trainId(), direction, headsign,
                p.lat(), p.lon(), TrainState.round(bearing, 1), TrainState.round(speed, 1), delay, status, source,
                prevStopId == null ? null : NetworkModel.publicId(sys, prevStopId),
                NetworkModel.publicId(sys, next.stopId()), nextStation.name(),
                nextArr.minus(offset), TrainState.round(progress, 3), data.minus(offset), "LIVE"));
    }

    private static String previousStopFromSchedule(SystemNetwork net, Optional<Trip> trip, String routeId,
                                                   String nextStopId) {
        if (trip.isPresent()) {
            int i = trip.get().indexOf(nextStopId);
            if (i > 0) {
                return trip.get().stopId(i - 1);
            }
            if (i == 0) {
                return null; // origin terminal
            }
        }
        return routeId == null ? null : net.predecessor(routeId, nextStopId).orElse(null);
    }

    private static int runTimeSeconds(SystemNetwork net, Optional<Trip> trip, String from, String to,
                                      Optional<Segment> segment) {
        if (trip.isPresent()) {
            int i = trip.get().indexOf(to);
            if (i > 0 && trip.get().stopId(i - 1).equals(from)) {
                return Math.max(1, trip.get().arrival(i) - trip.get().departure(i - 1));
            }
        }
        if (segment.isPresent()) {
            return Math.max(1, segment.get().scheduledRunSec());
        }
        double meters = net.stop(from).flatMap(a -> net.stop(to).map(b -> a.location().distanceTo(b.location())))
                .orElse(1000.0);
        return (int) Math.max(30, meters / FALLBACK_SPEED_MPS);
    }

    public static Integer delaySeconds(SystemNetwork net, Optional<Trip> trip, RtFeed.StopTimeUpdate next,
                                LocalDate serviceDate) {
        if (next.arrivalDelaySec() != null) {
            return next.arrivalDelaySec();
        }
        if (trip.isEmpty()) {
            return null;
        }
        int i = trip.get().indexOf(next.stopId());
        if (i < 0) {
            return null;
        }
        Instant scheduled = GtfsTime.at(serviceDate, net.zone(),
                next.arrival() != null ? trip.get().arrival(i) : trip.get().departure(i));
        return (int) Duration.between(scheduled, next.arrivalOrDeparture()).toSeconds();
    }
}
