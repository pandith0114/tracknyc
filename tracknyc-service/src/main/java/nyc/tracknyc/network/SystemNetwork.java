package nyc.tracknyc.network;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nyc.live.core.geo.BoundingBox;
import nyc.live.core.geo.Polyline;

/** The static network of one system (subway, LIRR, ...), immutable after construction. */
public final class SystemNetwork {

    private final String system;
    private final ZoneId zone;
    private final Map<String, Stop> stops;
    private final Map<String, Route> routes;
    private final Map<String, Trip> trips;
    private final Map<String, Polyline> shapes;
    private final Map<String, Segment> segments;
    private final Map<String, String> predecessors;
    private final Map<String, List<String>> childrenByStation;
    private final ServiceCalendar calendar;
    private final String fingerprint;

    public SystemNetwork(String system, ZoneId zone, Map<String, Stop> stops, Map<String, Route> routes,
                         Map<String, Trip> trips, Map<String, Polyline> shapes, Map<String, Segment> segments,
                         Map<String, String> predecessors, ServiceCalendar calendar, String fingerprint) {
        this.system = system;
        this.zone = zone;
        this.stops = Map.copyOf(stops);
        this.routes = Map.copyOf(routes);
        this.trips = Map.copyOf(trips);
        this.shapes = Map.copyOf(shapes);
        this.segments = Map.copyOf(segments);
        this.predecessors = Map.copyOf(predecessors);
        this.calendar = calendar;
        this.fingerprint = fingerprint;
        Map<String, List<String>> children = new HashMap<>();
        for (Stop s : stops.values()) {
            if (!s.isStation()) {
                children.computeIfAbsent(s.stationId(), k -> new ArrayList<>()).add(s.id());
            }
        }
        children.replaceAll((k, v) -> List.copyOf(v));
        this.childrenByStation = Map.copyOf(children);
    }

    public static String predecessorKey(String routeId, String stopId) {
        return routeId + '|' + stopId;
    }

    public String system() {
        return system;
    }

    public ZoneId zone() {
        return zone;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public Optional<Stop> stop(String id) {
        return Optional.ofNullable(stops.get(id));
    }

    /** The station a (platform) stop belongs to. */
    public Optional<Stop> stationOf(String stopId) {
        Stop s = stops.get(stopId);
        if (s == null) {
            return Optional.empty();
        }
        Stop station = stops.get(s.stationId());
        return Optional.of(station == null ? s : station);
    }

    /** Stations (parents) plus stand-alone stops without a parent, sorted by name. */
    public List<Stop> stations() {
        List<Stop> out = new ArrayList<>();
        for (Stop s : stops.values()) {
            if (s.isStation() || ((s.parentId() == null || s.parentId().isEmpty()) && s.locationType() == 0)) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing(Stop::name).thenComparing(Stop::id));
        return out;
    }

    /** Platform/child stop ids of a station (the station id itself if it has none). */
    public List<String> platformsOf(String stationId) {
        return childrenByStation.getOrDefault(stationId, List.of(stationId));
    }

    public Optional<Route> route(String id) {
        return Optional.ofNullable(routes.get(id));
    }

    public Collection<Route> routes() {
        return Collections.unmodifiableCollection(routes.values());
    }

    public Optional<Trip> trip(String id) {
        return Optional.ofNullable(trips.get(id));
    }

    public Collection<Trip> trips() {
        return Collections.unmodifiableCollection(trips.values());
    }

    public Map<String, Polyline> shapes() {
        return shapes;
    }

    public Optional<Segment> segment(String fromStopId, String toStopId) {
        return Optional.ofNullable(segments.get(Segment.key(fromStopId, toStopId)));
    }

    public Collection<Segment> segments() {
        return Collections.unmodifiableCollection(segments.values());
    }

    /** The stop before {@code stopId} on {@code routeId} in the static schedule (first trip seen). */
    public Optional<String> predecessor(String routeId, String stopId) {
        return Optional.ofNullable(predecessors.get(predecessorKey(routeId, stopId)));
    }

    public boolean isServiceActive(String serviceId, LocalDate date) {
        return calendar.isEmpty() || calendar.isActive(serviceId, date);
    }

    public Optional<BoundingBox> bounds() {
        BoundingBox box = null;
        for (Stop s : stops.values()) {
            BoundingBox b = new BoundingBox(s.location().lat(), s.location().lon(), s.location().lat(), s.location().lon());
            box = box == null ? b : box.union(b);
        }
        return Optional.ofNullable(box);
    }
}
