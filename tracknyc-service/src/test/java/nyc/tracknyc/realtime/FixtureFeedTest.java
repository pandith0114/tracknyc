package nyc.tracknyc.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.List;
import nyc.live.core.geo.MotionProfile;
import nyc.tracknyc.Fixtures;
import nyc.tracknyc.matching.NyctTripMatcher;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.position.PositionEngine;
import nyc.tracknyc.position.PositionMode;
import nyc.tracknyc.position.TrainState;
import org.junit.jupiter.api.Test;

/** Decodes the recorded (synthetic) protobuf fixtures and runs them through the engine. */
class FixtureFeedTest {

    @Test
    void decodesNyctExtensions() throws Exception {
        RtFeed feed = GtfsRtDecoder.decode("subway-l", Fixtures.realtime("subway-l", 10));
        assertTrue(feed.tripUpdates().size() > 10);
        assertTrue(feed.vehicles().size() > 5);
        RtFeed.TripUpdate tu = feed.tripUpdates().get(0);
        assertNotNull(tu.nyct(), "NyctTripDescriptor extension parsed");
        assertTrue(tu.nyct().trainId().startsWith("0L "));
        assertTrue(List.of("N", "S").contains(tu.nyct().direction()));
        RtFeed.StopTimeUpdate stu = tu.stopTimeUpdates().get(0);
        assertNotNull(stu.scheduledTrack(), "NyctStopTimeUpdate extension parsed");
        assertEquals("20261001", tu.startDate().toString().replace("-", ""));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(InvalidProtocolBufferException.class,
                () -> GtfsRtDecoder.decode("x", new byte[] {(byte) 0xFF, 0x13, 0x37}));
    }

    @Test
    void enginePlacesFixtureTrainsOnTheirLines() throws Exception {
        SystemNetwork net = Fixtures.network("subway");
        PositionEngine engine = new PositionEngine(PositionMode.INTERPOLATE, MotionProfile.LINEAR);
        for (String feedId : List.of("subway-1234567", "subway-l")) {
            RtFeed feed = GtfsRtDecoder.decode(feedId, Fixtures.realtime(feedId, 20));
            List<TrainState> trains = engine.compute(net, new NyctTripMatcher(net), feed, feed.headerTimestamp(),
                    Duration.ZERO);
            assertTrue(trains.size() >= 10, feedId + " has trains: " + trains.size());
            long matched = trains.stream().filter(t -> t.delaySec() != null).count();
            assertEquals(trains.size(), matched, "all synthetic trips match the schedule");
            for (TrainState t : trains) {
                // Every estimated position is close to one of the two stops it is between.
                var next = net.stop(t.nextStopId().substring("subway:".length())).orElseThrow().location();
                double d = next.distanceTo(nyc.live.core.geo.LatLon.of(t.lat(), t.lon()));
                assertTrue(d < 3000, t.id() + " is " + d + " m from its next stop");
                assertTrue(t.delaySec() >= -30 && t.delaySec() <= 900, "plausible delay " + t.delaySec());
            }
        }
    }
}
