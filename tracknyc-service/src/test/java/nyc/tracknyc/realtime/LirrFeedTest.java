package nyc.tracknyc.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import nyc.live.core.geo.LatLon;
import nyc.live.core.geo.MotionProfile;
import nyc.tracknyc.Fixtures;
import nyc.tracknyc.matching.ExactTripMatcher;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.position.PositionEngine;
import nyc.tracknyc.position.PositionMode;
import nyc.tracknyc.position.TrainState;
import org.junit.jupiter.api.Test;

/**
 * The LIRR feed exercises the parts of the pipeline the subway does not: exact trip matching,
 * delays published by the feed, the MTA railroad track extension, and real GPS positions.
 */
class LirrFeedTest {

    private final SystemNetwork lirr = Fixtures.network("lirr");
    private final PositionEngine engine = new PositionEngine(PositionMode.INTERPOLATE, MotionProfile.LINEAR);

    @Test
    void staticFeedHasOneBranchWithTwoPatterns() {
        assertEquals(21, lirr.stations().size());
        assertEquals(1, lirr.routes().size());
        assertEquals("Babylon Branch", lirr.route("10").orElseThrow().displayName());
        assertEquals(2, lirr.route("10").orElseThrow().type(), "route_type 2 = rail");
        assertTrue(lirr.trips().size() > 100);
        // Express trips skip stations, so the express segment Jamaica -> Lynbrook exists too.
        assertTrue(lirr.segment("129", "188").isPresent(), "local segment");
        assertTrue(lirr.segment("129", "153").isPresent(), "express segment");
        assertTrue(lirr.segment("129", "153").orElseThrow().lengthM()
                > lirr.segment("129", "188").orElseThrow().lengthM());
    }

    @Test
    void decodesRailroadTrackExtensionAndDelays() throws Exception {
        RtFeed feed = GtfsRtDecoder.decode("lirr", Fixtures.realtime("lirr", 30));
        assertTrue(feed.tripUpdates().size() >= 4, "trips: " + feed.tripUpdates().size());
        RtFeed.TripUpdate tu = feed.tripUpdates().get(0);
        assertNull(tu.nyct(), "railroad feeds carry no NyctTripDescriptor");
        RtFeed.StopTimeUpdate stu = tu.stopTimeUpdates().stream().filter(s -> s.arrival() != null).findFirst()
                .orElseThrow();
        assertNotNull(stu.track(), "MTA railroad track extension (1005) decoded");
        assertNotNull(stu.arrivalDelaySec(), "delay published by the feed");
        assertNotNull(stu.stopSequence());
    }

    @Test
    void placesTrainsUsingReportedGpsWhereAvailable() throws Exception {
        RtFeed feed = GtfsRtDecoder.decode("lirr", Fixtures.realtime("lirr", 30));
        List<TrainState> trains = engine.compute(lirr, new ExactTripMatcher(lirr), feed, feed.headerTimestamp(),
                Duration.ZERO);
        assertTrue(trains.size() >= 3, "trains: " + trains.size());
        assertTrue(trains.stream().anyMatch(t -> "GPS".equals(t.positionSource())), "some trains report GPS");
        assertTrue(trains.stream().anyMatch(t -> "INTERPOLATED".equals(t.positionSource())), "others are estimated");
        for (TrainState t : trains) {
            assertEquals("lirr", t.system());
            assertEquals("10", t.routeId());
            assertNotNull(t.delaySec(), "delay comes straight from the feed");
            // Everything is inside the Long Island corridor the fixture covers.
            assertTrue(t.lat() > 40.6 && t.lat() < 40.8, "lat " + t.lat());
            assertTrue(t.lon() > -74.1 && t.lon() < -73.3, "lon " + t.lon());
            LatLon next = lirr.stop(t.nextStopId().substring("lirr:".length())).orElseThrow().location();
            assertTrue(next.distanceTo(LatLon.of(t.lat(), t.lon())) < 20_000);
        }
    }

    @Test
    void exactMatcherFindsTheScheduledTrip() throws Exception {
        RtFeed feed = GtfsRtDecoder.decode("lirr", Fixtures.realtime("lirr", 30));
        ExactTripMatcher matcher = new ExactTripMatcher(lirr);
        for (RtFeed.TripUpdate tu : feed.tripUpdates()) {
            assertTrue(matcher.match(tu, java.time.LocalDate.of(2026, 10, 1)).isPresent(),
                    "unmatched trip " + tu.tripId());
        }
    }
}
