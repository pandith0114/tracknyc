package nyc.live.core.geo;

import java.util.Collection;

/** Axis-aligned lat/lon box. Does not handle the antimeridian (irrelevant for NYC). */
public record BoundingBox(double minLat, double minLon, double maxLat, double maxLon) {

    public BoundingBox {
        if (minLat > maxLat || minLon > maxLon) {
            throw new IllegalArgumentException("min must be <= max");
        }
    }

    public static BoundingBox of(Collection<LatLon> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("no points");
        }
        double minLat = 90;
        double minLon = 180;
        double maxLat = -90;
        double maxLon = -180;
        for (LatLon p : points) {
            minLat = Math.min(minLat, p.lat());
            maxLat = Math.max(maxLat, p.lat());
            minLon = Math.min(minLon, p.lon());
            maxLon = Math.max(maxLon, p.lon());
        }
        return new BoundingBox(minLat, minLon, maxLat, maxLon);
    }

    /** Box that contains a circle of {@code radiusM} metres around {@code center}. */
    public static BoundingBox around(LatLon center, double radiusM) {
        double dLat = Math.toDegrees(radiusM / GeoMath.EARTH_RADIUS_M);
        double cos = Math.max(1e-6, Math.cos(Math.toRadians(center.lat())));
        double dLon = dLat / cos;
        return new BoundingBox(
                Math.max(-90, center.lat() - dLat), Math.max(-180, center.lon() - dLon),
                Math.min(90, center.lat() + dLat), Math.min(180, center.lon() + dLon));
    }

    public boolean contains(LatLon p) {
        return p.lat() >= minLat && p.lat() <= maxLat && p.lon() >= minLon && p.lon() <= maxLon;
    }

    public boolean intersects(BoundingBox o) {
        return minLat <= o.maxLat && maxLat >= o.minLat && minLon <= o.maxLon && maxLon >= o.minLon;
    }

    public BoundingBox union(BoundingBox o) {
        return new BoundingBox(Math.min(minLat, o.minLat), Math.min(minLon, o.minLon),
                Math.max(maxLat, o.maxLat), Math.max(maxLon, o.maxLon));
    }

    /** Grows the box by {@code meters} on every side. */
    public BoundingBox expand(double meters) {
        BoundingBox lo = around(new LatLon(minLat, minLon), meters);
        BoundingBox hi = around(new LatLon(maxLat, maxLon), meters);
        return new BoundingBox(lo.minLat, lo.minLon, hi.maxLat, hi.maxLon);
    }

    public LatLon center() {
        return new LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2);
    }

    /** [west, south, east, north] — the order MapLibre's fitBounds and GeoJSON bbox use. */
    public double[] toLonLatArray() {
        return new double[] {minLon, minLat, maxLon, maxLat};
    }
}
