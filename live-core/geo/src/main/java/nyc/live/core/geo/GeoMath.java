package nyc.live.core.geo;

/** Small set of spherical-earth helpers. Accurate to well under a metre at city scale. */
public final class GeoMath {

    /** Mean earth radius (IUGG), metres. */
    public static final double EARTH_RADIUS_M = 6_371_008.8;

    private GeoMath() {
    }

    public static double haversineMeters(LatLon a, LatLon b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** Initial bearing from a to b in degrees clockwise from north, in [0, 360). */
    public static double initialBearing(LatLon a, LatLon b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return normalizeBearing(Math.toDegrees(Math.atan2(y, x)));
    }

    /** Point at the given distance and bearing from origin (spherical model). */
    public static LatLon destination(LatLon origin, double bearingDeg, double distanceM) {
        double delta = distanceM / EARTH_RADIUS_M;
        double theta = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(origin.lat());
        double lon1 = Math.toRadians(origin.lon());
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(delta)
                + Math.cos(lat1) * Math.sin(delta) * Math.cos(theta));
        double lon2 = lon1 + Math.atan2(Math.sin(theta) * Math.sin(delta) * Math.cos(lat1),
                Math.cos(delta) - Math.sin(lat1) * Math.sin(lat2));
        return new LatLon(Math.toDegrees(lat2), normalizeLon(Math.toDegrees(lon2)));
    }

    /** Linear interpolation in lat/lon space; fine for the short edges of a track shape. */
    public static LatLon lerp(LatLon a, LatLon b, double t) {
        return new LatLon(a.lat() + (b.lat() - a.lat()) * t, a.lon() + (b.lon() - a.lon()) * t);
    }

    public static double normalizeBearing(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    static double normalizeLon(double lon) {
        double l = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return l == -180.0 ? 180.0 : l;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
