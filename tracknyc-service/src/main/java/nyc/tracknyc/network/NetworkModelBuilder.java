package nyc.tracknyc.network;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nyc.live.core.geo.LatLon;
import nyc.live.core.geo.Polyline;
import nyc.tracknyc.gtfs.GtfsRows;
import nyc.tracknyc.gtfs.GtfsSink;

/**
 * Builds a {@link SystemNetwork} from GTFS rows. Segments are either computed here in Java
 * ({@link SegmentBuilder}, used by the file-based profile and tests) or supplied by the caller
 * (the PostGIS repository passes the segments PostGIS precomputed with ST_LineSubstring).
 */
public final class NetworkModelBuilder implements GtfsSink {

    private final String system;
    private final ZoneId defaultZone;
    private ZoneId zone;
    private final Map<String, Stop> stops = new LinkedHashMap<>();
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final Map<String, GtfsRows.TripRow> tripRows = new LinkedHashMap<>();
    private final Map<String, List<GtfsRows.StopTimeRow>> stopTimes = new HashMap<>();
    private final Map<String, List<GtfsRows.ShapePointRow>> shapePoints = new HashMap<>();
    private final ServiceCalendar calendar = new ServiceCalendar();
    private Collection<Segment> suppliedSegments;
    private String fingerprint = "";

    public NetworkModelBuilder(String system, ZoneId defaultZone) {
        this.system = system;
        this.defaultZone = defaultZone;
    }

    public NetworkModelBuilder fingerprint(String fp) {
        this.fingerprint = fp;
        return this;
    }

    /** Use these segments instead of computing them (e.g. precomputed in PostGIS). */
    public NetworkModelBuilder segments(Collection<Segment> segments) {
        this.suppliedSegments = segments;
        return this;
    }

    @Override
    public void agency(GtfsRows.AgencyRow row) {
        if (zone == null && row.timezone() != null && !row.timezone().isBlank()) {
            zone = ZoneId.of(row.timezone());
        }
    }

    @Override
    public void route(GtfsRows.RouteRow r) {
        routes.put(r.routeId(), new Route(r.routeId(), system, r.shortName(), r.longName(), r.routeType(),
                normalizeColor(r.color(), "808183"), normalizeColor(r.textColor(), "FFFFFF")));
    }

    static String normalizeColor(String c, String dflt) {
        if (c == null || c.isBlank()) {
            return dflt;
        }
        String s = c.startsWith("#") ? c.substring(1) : c;
        return s.matches("[0-9A-Fa-f]{6}") ? s.toUpperCase() : dflt;
    }

    @Override
    public void stop(GtfsRows.StopRow s) {
        stops.put(s.stopId(), new Stop(s.stopId(), s.name(), LatLon.of(s.lat(), s.lon()), s.locationType(),
                s.parentStation() == null || s.parentStation().isBlank() ? null : s.parentStation()));
    }

    @Override
    public void calendar(GtfsRows.CalendarRow row) {
        calendar.add(row);
    }

    @Override
    public void calendarDate(GtfsRows.CalendarDateRow row) {
        calendar.add(row);
    }

    @Override
    public void shapePoint(GtfsRows.ShapePointRow row) {
        shapePoints.computeIfAbsent(row.shapeId(), k -> new ArrayList<>()).add(row);
    }

    @Override
    public void trip(GtfsRows.TripRow row) {
        tripRows.put(row.tripId(), row);
    }

    @Override
    public void stopTime(GtfsRows.StopTimeRow row) {
        stopTimes.computeIfAbsent(row.tripId(), k -> new ArrayList<>()).add(row);
    }

    public SystemNetwork build() {
        Map<String, Polyline> shapes = new HashMap<>();
        shapePoints.forEach((id, pts) -> {
            pts.sort(Comparator.comparingInt(GtfsRows.ShapePointRow::sequence));
            List<LatLon> ll = new ArrayList<>(pts.size());
            for (GtfsRows.ShapePointRow p : pts) {
                ll.add(LatLon.of(p.lat(), p.lon()));
            }
            if (ll.size() >= 2) {
                shapes.put(id, Polyline.of(ll));
            }
        });

        Map<String, Trip> trips = new HashMap<>();
        Map<String, String> predecessors = new HashMap<>();
        for (GtfsRows.TripRow tr : tripRows.values()) {
            List<GtfsRows.StopTimeRow> sts = stopTimes.get(tr.tripId());
            if (sts == null || sts.size() < 2) {
                continue; // a trip that goes nowhere cannot be drawn
            }
            sts.sort(Comparator.comparingInt(GtfsRows.StopTimeRow::stopSequence));
            String[] ids = new String[sts.size()];
            int[] arr = new int[sts.size()];
            int[] dep = new int[sts.size()];
            for (int i = 0; i < sts.size(); i++) {
                ids[i] = sts.get(i).stopId();
                arr[i] = sts.get(i).arrival();
                dep[i] = sts.get(i).departure();
                if (i > 0) {
                    predecessors.putIfAbsent(SystemNetwork.predecessorKey(tr.routeId(), ids[i]), ids[i - 1]);
                }
            }
            trips.put(tr.tripId(), new Trip(tr.tripId(), tr.routeId(), tr.serviceId(), tr.headsign(),
                    tr.directionId(), tr.shapeId(), ids, arr, dep));
        }

        Map<String, Segment> segments = new HashMap<>();
        if (suppliedSegments != null) {
            for (Segment s : suppliedSegments) {
                segments.put(s.key(), s);
            }
        } else {
            segments.putAll(SegmentBuilder.build(trips.values(), stops, shapes));
        }
        return new SystemNetwork(system, zone == null ? defaultZone : zone, stops, routes, trips, shapes, segments,
                predecessors, calendar, fingerprint);
    }
}
