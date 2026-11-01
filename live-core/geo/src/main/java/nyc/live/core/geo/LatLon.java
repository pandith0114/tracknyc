package nyc.live.core.geo;

/**
 * A WGS84 coordinate. Latitude first, like people say it; note that PostGIS / GeoJSON
 * use (lon, lat) order, which {@link Wkt} and the GeoJSON writers take care of.
 */
public record LatLon(double lat, double lon) {

    public LatLon {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            throw new IllegalArgumentException("NaN coordinate");
        }
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("latitude out of range: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new IllegalArgumentException("longitude out of range: " + lon);
        }
    }

    public static LatLon of(double lat, double lon) {
        return new LatLon(lat, lon);
    }

    /** Great-circle distance in metres. */
    public double distanceTo(LatLon other) {
        return GeoMath.haversineMeters(this, other);
    }

    /** Rounds to the given number of decimals (6 decimals is ~0.1 m), useful for stable diffs. */
    public LatLon rounded(int decimals) {
        double f = Math.pow(10, decimals);
        return new LatLon(Math.round(lat * f) / f, Math.round(lon * f) / f);
    }
}
