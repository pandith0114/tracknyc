package nyc.tracknyc.app;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import nyc.live.core.geo.BoundingBox;
import nyc.live.core.geo.LatLon;
import nyc.tracknyc.network.NetworkModel;
import nyc.tracknyc.network.Route;
import nyc.tracknyc.network.Stop;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.network.Trip;
import nyc.tracknyc.position.TrainState;
import nyc.tracknyc.realtime.RtFeed;

/**
 * Builds the live departure board of a station and the detail view of one train, straight from the
 * newest decoded feeds (not from the computed positions, so a board shows trains that are still
 * far away).
 */
public class DepartureBoardService {

    /** One upcoming train at a station. */
    public record Departure(String trainId, String tripId, String routeId, String route, String color,
                            String headsign, String direction, Instant arrival, Instant departure,
                            long minutesAway, Integer delaySec, String track, String status) {
    }

    public record DepartureBoard(String stationId, String name, String system, double lat, double lon,
                                 List<String> routes, Instant generatedAt, List<Departure> departures) {
    }

    /** One stop on a train's remaining journey. */
    public record UpcomingStop(String stopId, String name, Instant arrival, Integer delaySec, String track,
                               boolean skipped) {
    }

    public record TrainDetail(TrainState train, String route, String color, String textColor,
                              List<UpcomingStop> upcomingStops) {
    }

    /** Stations near a point, for "trains near me". */
    public record NearbyStation(String stationId, String name, String system, double lat, double lon,
                                double distanceM, List<String> routes) {
    }

    static final Duration BOARD_WINDOW = Duration.ofMinutes(60);
    static final int BOARD_LIMIT = 24;

    private final NetworkLookup networks;
    private final TrainPipeline pipeline;
    private final TrainSnapshots live;

    public DepartureBoardService(NetworkLookup networks, TrainPipeline pipeline, TrainSnapshots live) {
        this.networks = networks;
        this.pipeline = pipeline;
        this.live = live;
    }

    /** @param stationPublicId e.g. {@code subway:L08} (a station or one of its platforms) */
    public Optional<DepartureBoard> board(String stationPublicId) {
        String[] parts = NetworkModel.splitPublicId(stationPublicId);
        if (parts == null) {
            return Optional.empty();
        }
        Optional<SystemNetwork> maybeNet = networks.system(parts[0]);
        if (maybeNet.isEmpty()) {
            return Optional.empty();
        }
        SystemNetwork net = maybeNet.get();
        Optional<Stop> maybeStop = net.stop(parts[1]);
        if (maybeStop.isEmpty()) {
            return Optional.empty();
        }
        Stop station = net.stationOf(parts[1]).orElse(maybeStop.get());
        Set<String> platforms = Set.copyOf(net.platformsOf(station.id()));
        Instant wall = pipeline.now();
        List<Departure> departures = new ArrayList<>();
        Set<String> routesHere = new java.util.TreeSet<>();

        for (TrainPipeline.DecodedFeed d : pipeline.latestFeeds()) {
            if (!d.system().equals(net.system())) {
                continue;
            }
            Instant feedNow = d.feedNow(wall);
            for (RtFeed.TripUpdate tu : d.feed().tripUpdates()) {
                if (tu.relationship() == RtFeed.TripRelationship.CANCELED) {
                    continue;
                }
                for (RtFeed.StopTimeUpdate stu : tu.stopTimeUpdates()) {
                    if (!platforms.contains(stu.stopId()) && !stu.stopId().equals(station.id())) {
                        continue;
                    }
                    Instant when = stu.arrivalOrDeparture();
                    if (stu.skipped() || when == null || when.isBefore(feedNow.minusSeconds(30))
                            || when.isAfter(feedNow.plus(BOARD_WINDOW))) {
                        continue;
                    }
                    Optional<Trip> trip = networks.matcher(net.system())
                            .flatMap(m -> m.match(tu, tu.startDate() != null ? tu.startDate()
                                    : feedNow.atZone(net.zone()).toLocalDate()));
                    String routeId = tu.routeId() != null ? tu.routeId() : trip.map(Trip::routeId).orElse(null);
                    Route route = routeId == null ? null : net.route(routeId).orElse(null);
                    if (routeId != null) {
                        routesHere.add(routeId);
                    }
                    Instant wallArrival = when.minus(d.clockOffset());
                    departures.add(new Departure(
                            NetworkModel.publicId(net.system(), tu.tripId()), tu.tripId(), routeId,
                            route == null ? routeId : route.displayName(),
                            route == null ? "#808183" : "#" + route.color(),
                            trip.map(Trip::headsign).filter(h -> !h.isBlank())
                                    .orElseGet(() -> lastStopName(net, tu)),
                            tu.nyct() != null ? tu.nyct().direction() : directionOf(stu.stopId(), station.id(), trip),
                            wallArrival,
                            stu.departureOrArrival() == null ? null : stu.departureOrArrival().minus(d.clockOffset()),
                            Math.max(0, Duration.between(feedNow, when).toMinutes()),
                            delayOf(trip, net, stu, tu, feedNow),
                            stu.track(),
                            Duration.between(feedNow, when).toSeconds() <= 30 ? "arriving" : "scheduled"));
                }
            }
        }
        departures.sort(Comparator.comparing(Departure::arrival));
        List<Departure> top = departures.size() > BOARD_LIMIT ? departures.subList(0, BOARD_LIMIT) : departures;
        return Optional.of(new DepartureBoard(NetworkModel.publicId(net.system(), station.id()), station.name(),
                net.system(), station.location().lat(), station.location().lon(), List.copyOf(routesHere), wall,
                List.copyOf(top)));
    }

    private static String lastStopName(SystemNetwork net, RtFeed.TripUpdate tu) {
        if (tu.stopTimeUpdates().isEmpty()) {
            return null;
        }
        String last = tu.stopTimeUpdates().get(tu.stopTimeUpdates().size() - 1).stopId();
        return net.stationOf(last).map(Stop::name).orElse(null);
    }

    private static String directionOf(String stopId, String stationId, Optional<Trip> trip) {
        if (stopId.length() == stationId.length() + 1) {
            return stopId.substring(stationId.length()); // NYCT platform suffix: N / S
        }
        return trip.map(t -> Integer.toString(t.directionId())).orElse(null);
    }

    private static Integer delayOf(Optional<Trip> trip, SystemNetwork net, RtFeed.StopTimeUpdate stu,
                                   RtFeed.TripUpdate tu, Instant feedNow) {
        return nyc.tracknyc.position.PositionEngine.delaySeconds(net, trip, stu,
                tu.startDate() != null ? tu.startDate() : feedNow.atZone(net.zone()).toLocalDate());
    }

    /** The selected train plus the stops it still has to make. */
    public Optional<TrainDetail> train(String trainPublicId) {
        TrainState state = live.trains().get(trainPublicId);
        if (state == null) {
            return Optional.empty();
        }
        SystemNetwork net = networks.system(state.system()).orElse(null);
        if (net == null) {
            return Optional.empty();
        }
        Route route = state.routeId() == null ? null : net.route(state.routeId()).orElse(null);
        List<UpcomingStop> stops = new ArrayList<>();
        pipeline.latest(state.feedId()).ifPresent(d -> {
            Instant feedNow = d.feedNow(pipeline.now());
            d.feed().tripUpdates().stream().filter(tu -> state.tripId().equals(tu.tripId())).findFirst()
                    .ifPresent(tu -> {
                        for (RtFeed.StopTimeUpdate stu : tu.stopTimeUpdates()) {
                            Instant when = stu.arrivalOrDeparture();
                            if (when == null || when.isBefore(feedNow.minusSeconds(60))) {
                                continue;
                            }
                            stops.add(new UpcomingStop(NetworkModel.publicId(net.system(), stu.stopId()),
                                    net.stationOf(stu.stopId()).map(Stop::name).orElse(stu.stopId()),
                                    when.minus(d.clockOffset()), stu.arrivalDelaySec(), stu.track(), stu.skipped()));
                        }
                    });
        });
        return Optional.of(new TrainDetail(state, route == null ? state.routeId() : route.displayName(),
                "#" + (route == null ? "808183" : route.color()), "#" + (route == null ? "FFFFFF" : route.textColor()),
                List.copyOf(stops)));
    }

    /** Stations within {@code radiusM} of a point, nearest first ("trains near me"). */
    public List<NearbyStation> nearbyStations(double lat, double lon, double radiusM, int limit) {
        LatLon me = LatLon.of(lat, lon);
        BoundingBox box = BoundingBox.around(me, radiusM);
        Map<String, List<String>> routesByStation = new java.util.HashMap<>();
        List<NearbyStation> out = new ArrayList<>();
        for (SystemNetwork net : networks.systems()) {
            for (Trip t : net.trips()) {
                for (int i = 0; i < t.stopCount(); i++) {
                    String station = net.stationOf(t.stopId(i)).map(Stop::id).orElse(t.stopId(i));
                    List<String> routes = routesByStation.computeIfAbsent(net.system() + ':' + station,
                            k -> new ArrayList<>());
                    if (!routes.contains(t.routeId())) {
                        routes.add(t.routeId());
                    }
                }
            }
            for (Stop s : net.stations()) {
                if (!box.contains(s.location())) {
                    continue;
                }
                double d = me.distanceTo(s.location());
                if (d > radiusM) {
                    continue;
                }
                List<String> routes = routesByStation.getOrDefault(net.system() + ':' + s.id(), List.of());
                if (routes.isEmpty()) {
                    continue;
                }
                routes.sort(String::compareToIgnoreCase);
                out.add(new NearbyStation(NetworkModel.publicId(net.system(), s.id()), s.name(), net.system(),
                        s.location().lat(), s.location().lon(), Math.round(d), List.copyOf(routes)));
            }
        }
        out.sort(Comparator.comparingDouble(NearbyStation::distanceM));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
    }
}
