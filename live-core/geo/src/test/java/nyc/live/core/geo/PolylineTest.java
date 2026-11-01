package nyc.live.core.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolylineTest {

    // One degree of latitude is ~111.2 km on the mean-radius sphere.
    private static final double M_PER_DEG_LAT = Math.toRadians(1) * GeoMath.EARTH_RADIUS_M;

    @Test
    void lengthOfMeridianSegment() {
        Polyline line = Polyline.of(LatLon.of(40.0, -74.0), LatLon.of(40.01, -74.0));
        assertEquals(0.01 * M_PER_DEG_LAT, line.lengthMeters(), 0.01);
    }

    @Test
    void interpolateEndpointsAndMidpoint() {
        Polyline line = Polyline.of(LatLon.of(40.0, -74.0), LatLon.of(40.02, -74.0));
        assertEquals(LatLon.of(40.0, -74.0), line.interpolate(0));
        assertEquals(LatLon.of(40.02, -74.0), line.interpolate(1));
        LatLon mid = line.interpolate(0.5);
        assertEquals(40.01, mid.lat(), 1e-9);
        assertEquals(-74.0, mid.lon(), 1e-9);
    }

    @Test
    void interpolateClampsOutOfRangeFractions() {
        Polyline line = Polyline.of(LatLon.of(40.0, -74.0), LatLon.of(40.02, -74.0));
        assertEquals(line.start(), line.interpolate(-0.3));
        assertEquals(line.end(), line.interpolate(1.7));
    }

    @Test
    void geodesicAndPlanarInterpolationDifferOnBentLines() {
        // Leg 1 runs north 0.01 deg (~1112 m), leg 2 runs east 0.01 deg (~843 m at 40.7N).
        Polyline bent = Polyline.of(LatLon.of(40.70, -74.00), LatLon.of(40.71, -74.00), LatLon.of(40.71, -73.99));
        // Planar: both legs are 0.01 "degrees" long, so 50% is exactly the corner.
        LatLon planarMid = bent.interpolatePlanar(0.5);
        assertEquals(40.71, planarMid.lat(), 1e-9);
        assertEquals(-74.00, planarMid.lon(), 1e-9);
        // Geodesic: 50% of ~1955 m is ~978 m, i.e. still on the northbound leg.
        LatLon geoMid = bent.interpolate(0.5);
        assertTrue(geoMid.lat() < 40.71, "geodesic midpoint should be before the corner");
        assertEquals(bent.lengthMeters() / 2, GeoMath.haversineMeters(bent.start(), geoMid), 0.5);
    }

    @Test
    void bearingFollowsTheEdgeBeingTravelled() {
        Polyline bent = Polyline.of(LatLon.of(40.70, -74.00), LatLon.of(40.71, -74.00), LatLon.of(40.71, -73.99));
        assertEquals(0.0, bent.bearingAt(0.1), 0.01);  // north
        assertEquals(90.0, bent.bearingAt(0.9), 0.01); // east (tiny great-circle deviation)
    }

    @Test
    void locateIsInverseOfInterpolate() {
        Polyline line = Polyline.of(LatLon.of(40.70, -74.00), LatLon.of(40.71, -74.00), LatLon.of(40.72, -73.98));
        for (double f : new double[] {0.0, 0.1, 0.37, 0.5, 0.81, 1.0}) {
            assertEquals(f, line.locate(line.interpolate(f)), 1e-6, "fraction " + f);
            assertEquals(f, line.locatePlanar(line.interpolatePlanar(f)), 1e-6, "planar fraction " + f);
        }
    }

    @Test
    void locateProjectsOffLinePoints() {
        Polyline line = Polyline.of(LatLon.of(40.70, -74.00), LatLon.of(40.72, -74.00));
        // A station platform 30 m east of the track centre line, halfway along.
        LatLon offset = GeoMath.destination(LatLon.of(40.71, -74.00), 90, 30);
        assertEquals(0.5, line.locate(offset), 1e-4);
    }

    @Test
    void substringBetweenFractions() {
        Polyline line = Polyline.of(LatLon.of(40.70, -74.00), LatLon.of(40.71, -74.00), LatLon.of(40.72, -74.00));
        Polyline sub = line.substring(0.25, 0.75);
        assertEquals(3, sub.size()); // start, the interior vertex, end
        assertEquals(line.lengthMeters() / 2, sub.lengthMeters(), 0.01);
        assertEquals(40.705, sub.start().lat(), 1e-9);
        assertEquals(40.715, sub.end().lat(), 1e-9);
    }

    @Test
    void substringWithReversedFractionsRunsBackwards() {
        Polyline line = Polyline.of(LatLon.of(40.70, -74.00), LatLon.of(40.72, -74.00));
        Polyline sub = line.substring(0.75, 0.25);
        assertTrue(sub.start().lat() > sub.end().lat());
        assertEquals(180.0, sub.bearingAt(0.5), 0.01);
    }

    @Test
    void appendDropsDuplicateJoinPoint() {
        Polyline a = Polyline.of(LatLon.of(40.70, -74.00), LatLon.of(40.71, -74.00));
        Polyline b = Polyline.of(LatLon.of(40.71, -74.00), LatLon.of(40.72, -74.00));
        assertEquals(3, a.append(b).size());
    }

    @Test
    void degenerateLinesAreSafe() {
        Polyline single = Polyline.of(List.of(LatLon.of(40.7, -74.0)));
        assertEquals(0.0, single.lengthMeters(), 0);
        assertEquals(LatLon.of(40.7, -74.0), single.interpolate(0.5));
        assertEquals(0.0, single.locate(LatLon.of(40.8, -74.0)), 0);
        assertEquals(0.0, single.bearingAt(0.5), 0);
        assertThrows(IllegalArgumentException.class, () -> Polyline.of(List.of()));
    }
}
