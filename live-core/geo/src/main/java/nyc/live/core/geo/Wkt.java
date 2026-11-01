package nyc.live.core.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal WKT reader/writer for POINT and LINESTRING (all this project exchanges with PostGIS).
 * Coordinates are written "lon lat", as WKT requires.
 */
public final class Wkt {

    private Wkt() {
    }

    public static String point(LatLon p) {
        return String.format(Locale.ROOT, "POINT(%.7f %.7f)", p.lon(), p.lat());
    }

    public static String lineString(List<LatLon> pts) {
        if (pts.size() < 2) {
            throw new IllegalArgumentException("LINESTRING needs at least 2 points");
        }
        StringBuilder sb = new StringBuilder(pts.size() * 24).append("LINESTRING(");
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.7f %.7f", pts.get(i).lon(), pts.get(i).lat()));
        }
        return sb.append(')').toString();
    }

    public static String lineString(Polyline line) {
        return lineString(line.points());
    }

    public static LatLon parsePoint(String wkt) {
        List<LatLon> pts = parseCoords(wkt, "POINT");
        if (pts.size() != 1) {
            throw new IllegalArgumentException("not a POINT: " + wkt);
        }
        return pts.get(0);
    }

    public static Polyline parseLineString(String wkt) {
        return Polyline.of(parseCoords(wkt, "LINESTRING"));
    }

    private static List<LatLon> parseCoords(String wkt, String type) {
        String s = wkt.trim();
        // Accept EWKT "SRID=4326;LINESTRING(...)".
        int semi = s.indexOf(';');
        if (s.regionMatches(true, 0, "SRID=", 0, 5) && semi > 0) {
            s = s.substring(semi + 1).trim();
        }
        if (!s.regionMatches(true, 0, type, 0, type.length())) {
            throw new IllegalArgumentException("expected " + type + ": " + wkt);
        }
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (open < 0 || close < open) {
            throw new IllegalArgumentException("malformed WKT: " + wkt);
        }
        String body = s.substring(open + 1, close).trim();
        List<LatLon> out = new ArrayList<>();
        if (body.isEmpty()) {
            return out;
        }
        for (String pair : body.split(",")) {
            String[] xy = pair.trim().split("\\s+");
            if (xy.length < 2) {
                throw new IllegalArgumentException("malformed coordinate '" + pair + "'");
            }
            out.add(new LatLon(Double.parseDouble(xy[1]), Double.parseDouble(xy[0])));
        }
        return out;
    }
}
