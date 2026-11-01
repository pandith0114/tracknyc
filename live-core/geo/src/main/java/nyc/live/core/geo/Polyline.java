package nyc.live.core.geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable WGS84 line string with precomputed cumulative lengths.
 *
 * <p>This is the in-memory twin of the PostGIS functions the project relies on:
 * <ul>
 *   <li>{@link #interpolate(double)} ~ {@code ST_LineInterpolatePoint} on a metric projection
 *       (fractions are of the <em>geodesic</em> length, so equal time steps give equal metres);</li>
 *   <li>{@link #interpolatePlanar(double)} ~ {@code ST_LineInterpolatePoint(geometry(4326))}
 *       (fractions of the length measured in degrees, exactly what PostGIS does on SRID 4326);</li>
 *   <li>{@link #locate(LatLon)} ~ {@code ST_LineLocatePoint};</li>
 *   <li>{@link #substring(double, double)} ~ {@code ST_LineSubstring}.</li>
 * </ul>
 * The integration tests check that the Java and PostGIS answers agree.
 */
public final class Polyline {

    private final List<LatLon> points;
    private final double[] cumMeters;
    private final double[] cumPlanar;

    private Polyline(List<LatLon> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("a polyline needs at least one point");
        }
        this.points = List.copyOf(points);
        this.cumMeters = new double[points.size()];
        this.cumPlanar = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            LatLon a = points.get(i - 1);
            LatLon b = points.get(i);
            cumMeters[i] = cumMeters[i - 1] + GeoMath.haversineMeters(a, b);
            cumPlanar[i] = cumPlanar[i - 1] + Math.hypot(b.lat() - a.lat(), b.lon() - a.lon());
        }
    }

    public static Polyline of(List<LatLon> points) {
        return new Polyline(points);
    }

    public static Polyline of(LatLon... points) {
        return new Polyline(List.of(points));
    }

    /** Straight two-point line, used when no track shape is known for a segment. */
    public static Polyline straight(LatLon from, LatLon to) {
        return new Polyline(List.of(from, to));
    }

    public List<LatLon> points() {
        return points;
    }

    public int size() {
        return points.size();
    }

    public LatLon start() {
        return points.get(0);
    }

    public LatLon end() {
        return points.get(points.size() - 1);
    }

    public double lengthMeters() {
        return cumMeters[cumMeters.length - 1];
    }

    double planarLength() {
        return cumPlanar[cumPlanar.length - 1];
    }

    /** Point at {@code fraction} (0..1, clamped) of the geodesic length. */
    public LatLon interpolate(double fraction) {
        return interpolateBy(cumMeters, fraction);
    }

    /** Point at {@code fraction} of the planar (degree) length, matching PostGIS on SRID 4326. */
    public LatLon interpolatePlanar(double fraction) {
        return interpolateBy(cumPlanar, fraction);
    }

    private LatLon interpolateBy(double[] cum, double fraction) {
        double f = GeoMath.clamp(fraction, 0.0, 1.0);
        double total = cum[cum.length - 1];
        if (points.size() == 1 || total == 0.0) {
            return points.get(0);
        }
        double target = f * total;
        int i = edgeIndexFor(cum, target);
        double edgeLen = cum[i + 1] - cum[i];
        double t = edgeLen == 0 ? 0 : (target - cum[i]) / edgeLen;
        return GeoMath.lerp(points.get(i), points.get(i + 1), t);
    }

    /** Index i of the edge [i, i+1] that contains the given cumulative distance. */
    private static int edgeIndexFor(double[] cum, double target) {
        int lo = 0;
        int hi = cum.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cum[mid] <= target) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Direction of travel at {@code fraction}, degrees clockwise from north. */
    public double bearingAt(double fraction) {
        if (points.size() < 2 || lengthMeters() == 0.0) {
            return 0.0;
        }
        double target = GeoMath.clamp(fraction, 0.0, 1.0) * lengthMeters();
        int i = edgeIndexFor(cumMeters, target);
        // Skip zero-length edges so the bearing is always defined.
        int j = i;
        while (j < points.size() - 2 && cumMeters[j + 1] - cumMeters[j] == 0.0) {
            j++;
        }
        return GeoMath.initialBearing(points.get(j), points.get(j + 1));
    }

    /** Fraction (0..1) of the geodesic length at the point on the line closest to {@code p}. */
    public double locate(LatLon p) {
        return locateBy(cumMeters, p);
    }

    /** Fraction of the planar length at the closest point (like ST_LineLocatePoint on SRID 4326). */
    public double locatePlanar(LatLon p) {
        return locateBy(cumPlanar, p);
    }

    private double locateBy(double[] cum, LatLon p) {
        if (points.size() == 1 || cum[cum.length - 1] == 0.0) {
            return 0.0;
        }
        double cosLat = Math.cos(Math.toRadians(p.lat()));
        double best = Double.MAX_VALUE;
        double bestAlong = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            LatLon a = points.get(i);
            LatLon b = points.get(i + 1);
            // Project in a local equirectangular frame (good to centimetres over a few km).
            double ax = a.lon() * cosLat;
            double ay = a.lat();
            double bx = b.lon() * cosLat;
            double by = b.lat();
            double px = p.lon() * cosLat;
            double py = p.lat();
            double dx = bx - ax;
            double dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : GeoMath.clamp(((px - ax) * dx + (py - ay) * dy) / len2, 0, 1);
            double cx = ax + t * dx;
            double cy = ay + t * dy;
            double d2 = (px - cx) * (px - cx) + (py - cy) * (py - cy);
            if (d2 < best) {
                best = d2;
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i]);
            }
        }
        return bestAlong / cum[cum.length - 1];
    }

    /**
     * Sub-line between two fractions of the geodesic length. If {@code from > to} the
     * result runs backwards (useful when a stop pair is travelled against the shape direction).
     */
    public Polyline substring(double from, double to) {
        double f0 = GeoMath.clamp(from, 0, 1);
        double f1 = GeoMath.clamp(to, 0, 1);
        if (f0 > f1) {
            return substring(f1, f0).reverse();
        }
        double total = lengthMeters();
        if (total == 0.0 || points.size() == 1) {
            return Polyline.of(points.get(0), points.get(0));
        }
        double d0 = f0 * total;
        double d1 = f1 * total;
        List<LatLon> out = new ArrayList<>();
        out.add(interpolate(f0));
        for (int i = 1; i < points.size() - 1; i++) {
            if (cumMeters[i] > d0 && cumMeters[i] < d1) {
                out.add(points.get(i));
            }
        }
        out.add(interpolate(f1));
        return new Polyline(out);
    }

    public Polyline reverse() {
        List<LatLon> copy = new ArrayList<>(points);
        Collections.reverse(copy);
        return new Polyline(copy);
    }

    public BoundingBox bounds() {
        return BoundingBox.of(points);
    }

    /** Concatenates two lines; a duplicated join point is dropped. */
    public Polyline append(Polyline next) {
        List<LatLon> out = new ArrayList<>(points);
        List<LatLon> other = next.points();
        int startIdx = (!other.isEmpty() && other.get(0).equals(end())) ? 1 : 0;
        out.addAll(other.subList(startIdx, other.size()));
        return new Polyline(out);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Polyline other && points.equals(other.points);
    }

    @Override
    public int hashCode() {
        return points.hashCode();
    }

    @Override
    public String toString() {
        return "Polyline[" + points.size() + " pts, " + Math.round(lengthMeters()) + " m]";
    }
}
