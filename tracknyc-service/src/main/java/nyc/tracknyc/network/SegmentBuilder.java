package nyc.tracknyc.network;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import nyc.live.core.geo.LatLon;
import nyc.live.core.geo.Polyline;

/**
 * Java implementation of the segment precomputation that PostGIS does in
 * {@code tracknyc_rebuild_segments()}: for every pair of consecutive stops, cut the trip's
 * shape between the points closest to the two stops ({@code ST_LineLocatePoint} +
 * {@code ST_LineSubstring}). Falls back to a straight line when there is no usable shape.
 */
public final class SegmentBuilder {

    /** If a stop is further than this from the shape, the shape is not trusted for it. */
    static final double MAX_SNAP_DISTANCE_M = 250;

    private SegmentBuilder() {
    }

    public static Map<String, Segment> build(Collection<Trip> trips, Map<String, Stop> stops,
                                             Map<String, Polyline> shapes) {
        Map<String, Segment> out = new HashMap<>();
        Map<String, Double> locateCache = new HashMap<>();
        for (Trip t : trips) {
            Polyline shape = t.shapeId() == null ? null : shapes.get(t.shapeId());
            for (int i = 1; i < t.stopCount(); i++) {
                String from = t.stopId(i - 1);
                String to = t.stopId(i);
                String key = Segment.key(from, to);
                if (out.containsKey(key)) {
                    continue;
                }
                Stop a = stops.get(from);
                Stop b = stops.get(to);
                if (a == null || b == null) {
                    continue;
                }
                int run = Math.max(1, t.arrival(i) - t.departure(i - 1));
                Polyline geom = cut(shape, t.shapeId(), a, b, locateCache);
                out.put(key, new Segment(from, to, geom == null ? null : t.shapeId(),
                        geom == null ? Polyline.straight(a.location(), b.location()) : geom,
                        (geom == null ? Polyline.straight(a.location(), b.location()) : geom).lengthMeters(), run));
            }
        }
        return out;
    }

    static Polyline cut(Polyline shape, String shapeId, Stop a, Stop b, Map<String, Double> cache) {
        if (shape == null) {
            return null;
        }
        double fa = cache.computeIfAbsent(shapeId + '|' + a.id(), k -> shape.locate(a.location()));
        double fb = cache.computeIfAbsent(shapeId + '|' + b.id(), k -> shape.locate(b.location()));
        if (fb <= fa) {
            return null; // stop order disagrees with shape direction (loops, bad data)
        }
        LatLon pa = shape.interpolate(fa);
        LatLon pb = shape.interpolate(fb);
        if (pa.distanceTo(a.location()) > MAX_SNAP_DISTANCE_M || pb.distanceTo(b.location()) > MAX_SNAP_DISTANCE_M) {
            return null;
        }
        return shape.substring(fa, fb);
    }
}
