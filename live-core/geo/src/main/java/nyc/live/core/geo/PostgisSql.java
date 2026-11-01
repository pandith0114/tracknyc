package nyc.live.core.geo;

import java.util.regex.Pattern;

/**
 * SQL fragments for common PostGIS operations. Values are always bound as JDBC parameters;
 * only (validated) identifiers are concatenated.
 */
public final class PostgisSql {

    /** Metric CRS used for interpolation: UTM zone 18N covers the whole NYC region. */
    public static final int METRIC_SRID = 32618;
    public static final int WGS84 = 4326;

    private static final Pattern IDENT = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.]*");

    private PostgisSql() {
    }

    static String ident(String column) {
        if (!IDENT.matcher(column).matches()) {
            throw new IllegalArgumentException("bad SQL identifier: " + column);
        }
        return column;
    }

    /** {@code ST_SetSRID(ST_MakePoint(?, ?), 4326)}; bind lon, lat. */
    public static String pointParam() {
        return "ST_SetSRID(ST_MakePoint(?, ?), 4326)";
    }

    /** Rows whose geometry lies within {@code ?} metres of a point; bind lon, lat, metres. */
    public static String withinMeters(String geomColumn) {
        return "ST_DWithin(" + ident(geomColumn) + "::geography, " + pointParam() + "::geography, ?)";
    }

    /** Distance in metres to a point; bind lon, lat. */
    public static String distanceMeters(String geomColumn) {
        return "ST_Distance(" + ident(geomColumn) + "::geography, " + pointParam() + "::geography)";
    }

    /** Bounding-box filter that can use a GiST index; bind minLon, minLat, maxLon, maxLat. */
    public static String intersectsEnvelope(String geomColumn) {
        return ident(geomColumn) + " && ST_MakeEnvelope(?, ?, ?, ?, 4326)";
    }

    public static Object[] envelopeParams(BoundingBox b) {
        return new Object[] {b.minLon(), b.minLat(), b.maxLon(), b.maxLat()};
    }

    /**
     * Point at a fraction of a line, interpolated in metres (UTM 18N) and returned as WKT in
     * WGS84; bind the fraction. Equivalent to {@link Polyline#interpolate(double)}.
     */
    public static String interpolateMetric(String lineColumn) {
        return "ST_AsText(ST_Transform(ST_LineInterpolatePoint(ST_Transform(" + ident(lineColumn)
                + ", " + METRIC_SRID + "), ?), " + WGS84 + "))";
    }

    /** Sub-line between two located points of a shape (both bound as lon/lat pairs). */
    public static String substringBetweenPoints(String lineColumn) {
        String c = ident(lineColumn);
        return "ST_LineSubstring(" + c + ", ST_LineLocatePoint(" + c + ", " + pointParam()
                + "), ST_LineLocatePoint(" + c + ", " + pointParam() + "))";
    }
}
