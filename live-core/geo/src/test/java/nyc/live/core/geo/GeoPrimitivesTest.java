package nyc.live.core.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeoPrimitivesTest {

    @Test
    void haversineKnownDistance() {
        // Two points 0.01 deg of longitude apart at 40.7N: 0.01 * 111195 * cos(40.7deg) ~ 843 m.
        double d = GeoMath.haversineMeters(LatLon.of(40.7, -74.0), LatLon.of(40.7, -73.99));
        assertEquals(842.9, d, 0.5);
    }

    @Test
    void bearingsOfCardinalDirections() {
        LatLon o = LatLon.of(40.7, -74.0);
        assertEquals(0.0, GeoMath.initialBearing(o, LatLon.of(40.8, -74.0)), 1e-6);
        assertEquals(180.0, GeoMath.initialBearing(o, LatLon.of(40.6, -74.0)), 1e-6);
        assertEquals(90.0, GeoMath.initialBearing(o, LatLon.of(40.7, -73.9)), 0.05);
        assertEquals(270.0, GeoMath.initialBearing(o, LatLon.of(40.7, -74.1)), 0.05);
    }

    @Test
    void destinationRoundTrip() {
        LatLon o = LatLon.of(40.75, -73.98);
        LatLon d = GeoMath.destination(o, 37, 1500);
        assertEquals(1500, GeoMath.haversineMeters(o, d), 0.01);
        assertEquals(37, GeoMath.initialBearing(o, d), 0.01);
    }

    @Test
    void latLonValidates() {
        assertThrows(IllegalArgumentException.class, () -> LatLon.of(91, 0));
        assertThrows(IllegalArgumentException.class, () -> LatLon.of(0, 181));
        assertThrows(IllegalArgumentException.class, () -> LatLon.of(Double.NaN, 0));
        assertEquals(LatLon.of(40.123457, -73.123457), LatLon.of(40.1234567, -73.1234567).rounded(6));
    }

    @Test
    void boundingBoxAroundPointContainsCircle() {
        LatLon c = LatLon.of(40.75, -73.98);
        BoundingBox box = BoundingBox.around(c, 1000);
        for (int b = 0; b < 360; b += 15) {
            assertTrue(box.contains(GeoMath.destination(c, b, 999)), "bearing " + b);
        }
        assertFalse(box.contains(GeoMath.destination(c, 0, 1100)));
    }

    @Test
    void boundingBoxOfPointsAndUnion() {
        BoundingBox a = BoundingBox.of(List.of(LatLon.of(40.7, -74.0), LatLon.of(40.8, -73.9)));
        assertEquals(40.7, a.minLat(), 0);
        assertEquals(-73.9, a.maxLon(), 0);
        BoundingBox b = new BoundingBox(40.75, -73.95, 40.9, -73.8);
        assertTrue(a.intersects(b));
        BoundingBox u = a.union(b);
        assertEquals(40.9, u.maxLat(), 0);
        assertEquals(-74.0, u.minLon(), 0);
        assertTrue(a.expand(100).contains(LatLon.of(40.6995, -74.0005)));
    }

    @Test
    void wktRoundTrip() {
        Polyline line = Polyline.of(LatLon.of(40.7, -74.0), LatLon.of(40.71, -73.99));
        String wkt = Wkt.lineString(line);
        assertEquals("LINESTRING(-74.0000000 40.7000000,-73.9900000 40.7100000)", wkt);
        assertEquals(line, Wkt.parseLineString(wkt));
        assertEquals(LatLon.of(40.7, -74.0), Wkt.parsePoint("SRID=4326;POINT(-74 40.7)"));
        assertThrows(IllegalArgumentException.class, () -> Wkt.parsePoint("LINESTRING(0 0, 1 1)"));
    }

    @Test
    void motionProfiles() {
        assertEquals(0.5, MotionProfile.LINEAR.distanceFraction(0.5), 1e-12);
        MotionProfile trap = MotionProfile.trapezoidal(0.25);
        assertEquals(0.0, trap.distanceFraction(0), 1e-12);
        assertEquals(1.0, trap.distanceFraction(1), 1e-12);
        assertEquals(0.5, trap.distanceFraction(0.5), 1e-12); // symmetric
        assertTrue(trap.distanceFraction(0.1) < 0.1, "slower than linear while accelerating");
        assertTrue(trap.distanceFraction(0.9) > 0.9, "ahead of linear while braking");
        double prev = 0;
        for (int i = 1; i <= 100; i++) {
            double d = trap.distanceFraction(i / 100.0);
            assertTrue(d >= prev, "monotonic");
            prev = d;
        }
        assertEquals(MotionProfile.LINEAR, MotionProfile.parse("linear"));
        assertThrows(IllegalArgumentException.class, () -> MotionProfile.parse("bouncy"));
    }

    @Test
    void postgisSqlRejectsBadIdentifiers() {
        assertEquals("ST_DWithin(s.geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)",
                PostgisSql.withinMeters("s.geom"));
        assertThrows(IllegalArgumentException.class, () -> PostgisSql.withinMeters("geom; DROP TABLE x"));
    }
}
