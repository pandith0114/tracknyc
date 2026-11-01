package nyc.tracknyc.position;

import static nyc.tracknyc.position.TestNetworks.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import nyc.live.core.geo.MotionProfile;
import nyc.tracknyc.matching.ExactTripMatcher;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.realtime.RtFeed;
import org.junit.jupiter.api.Test;

class PositionEngineTest {

    private final SystemNetwork net = TestNetworks.line();
    private final ExactTripMatcher matcher = new ExactTripMatcher(net);
    private final PositionEngine engine = new PositionEngine(PositionMode.INTERPOLATE, MotionProfile.LINEAR);

    private static RtFeed.StopTimeUpdate stu(String stop, Instant arr, Instant dep) {
        return new RtFeed.StopTimeUpdate(stop, null, arr, dep, null, false, null, null);
    }

    /** Trip T1 after leaving A: the subway feed has already dropped the passed stop. */
    private static RtFeed.TripUpdate t1(int delaySec, RtFeed.NyctTrip nyct, String entity, Instant ts) {
        return new RtFeed.TripUpdate(entity, "T1", "X", TestNetworks.DAY, null, RtFeed.TripRelationship.SCHEDULED,
                nyct, ts, List.of(
                stu("B", at("08:02:00").plusSeconds(delaySec), at("08:02:30").plusSeconds(delaySec)),
                stu("C", at("08:04:30").plusSeconds(delaySec), null)));
    }

    private static RtFeed feed(RtFeed.TripUpdate... tus) {
        return new RtFeed("test", at("08:00:00"), List.of(tus), List.of());
    }

    private List<TrainState> run(RtFeed feed, Instant now) {
        return engine.compute(net, matcher, feed, now, Duration.ZERO);
    }

    @Test
    void knownTripUpdateGivesExpectedCoordinates() {
        // Left A at 08:00:00 (from the schedule), due at B 08:02:00; at 08:01:00 it is halfway.
        TrainState t = run(feed(t1(0, null, "1", null)), at("08:01:00")).get(0);
        assertEquals(40.705, t.lat(), 1e-6);
        assertEquals(-74.0, t.lon(), 1e-6);
        assertEquals(0.0, t.bearing(), 0.05);
        assertEquals(0.5, t.progress(), 1e-9);
        assertEquals("IN_TRANSIT", t.status());
        assertEquals("INTERPOLATED", t.positionSource());
        assertEquals("subway:A", t.prevStopId());
        assertEquals("subway:B", t.nextStopId());
        assertEquals("Bravo", t.nextStopName());
        assertEquals(Integer.valueOf(0), t.delaySec());
        assertEquals("subway:T1", t.id());
        assertEquals("Charlie", t.headsign());
        // 1112 m in 120 s
        assertEquals(9.3, t.speedMps(), 0.05);
    }

    @Test
    void delayedTrainIsPlacedByPredictionsAndReportsDelay() {
        TrainState t = run(feed(t1(180, null, "1", null)), at("08:04:00")).get(0);
        assertEquals(40.705, t.lat(), 1e-6);
        assertEquals(Integer.valueOf(180), t.delaySec());
        assertEquals(at("08:05:00"), t.nextArrival());
    }

    @Test
    void secondSegmentUsesRealtimeDepartureOfPreviousStop() {
        // At 08:03:30 B is in the past (departed 08:02:30) and C is due 08:04:30.
        TrainState t = run(feed(t1(0, null, "1", null)), at("08:03:30")).get(0);
        assertEquals(40.715, t.lat(), 1e-6);
        assertEquals("subway:B", t.prevStopId());
        assertEquals("subway:C", t.nextStopId());
    }

    @Test
    void dwellingTrainSitsAtTheStation() {
        TrainState t = run(feed(t1(0, null, "1", null)), at("08:02:10")).get(0);
        assertEquals("AT_STATION", t.status());
        assertEquals(40.71, t.lat(), 1e-9);
        assertEquals(1.0, t.progress(), 0);
    }

    @Test
    void nextStopModePlacesTrainAtNextStation() {
        PositionEngine m1 = new PositionEngine(PositionMode.NEXT_STOP, MotionProfile.LINEAR);
        TrainState t = m1.compute(net, matcher, feed(t1(0, null, "1", null)), at("08:01:00"), Duration.ZERO).get(0);
        assertEquals(40.71, t.lat(), 1e-9);
        assertEquals("NEXT_STOP", t.positionSource());
    }

    @Test
    void trapezoidalProfileStartsSlowerButMeetsAtMidpoint() {
        PositionEngine trap = new PositionEngine(PositionMode.INTERPOLATE, MotionProfile.trapezoidal(0.25));
        TrainState mid = trap.compute(net, matcher, feed(t1(0, null, "1", null)), at("08:01:00"), Duration.ZERO).get(0);
        assertEquals(40.705, mid.lat(), 1e-6);
        TrainState early = trap.compute(net, matcher, feed(t1(0, null, "1", null)), at("08:00:15"), Duration.ZERO).get(0);
        assertTrue(early.lat() < 40.70 + 0.01 * 0.125, "slower than linear while accelerating");
    }

    @Test
    void clockOffsetShiftsOutputTimesToWallClock() {
        Duration offset = Duration.ofHours(-10); // replay: feed clock is 10 h behind the wall clock
        TrainState t = engine.compute(net, matcher, feed(t1(0, null, "1", null)), at("08:01:00"), offset).get(0);
        assertEquals(at("08:02:00").plus(Duration.ofHours(10)), t.nextArrival());
        assertEquals(40.705, t.lat(), 1e-6, "position computed in feed time");
    }

    @Test
    void unassignedCanceledAndFinishedTripsAreNotDrawn() {
        assertTrue(run(feed(t1(0, new RtFeed.NyctTrip("0X 0800", false, "N"), "1", null)), at("08:01:00")).isEmpty());
        RtFeed.TripUpdate canceled = new RtFeed.TripUpdate("1", "T1", "X", TestNetworks.DAY, null,
                RtFeed.TripRelationship.CANCELED, null, null, t1(0, null, "1", null).stopTimeUpdates());
        assertTrue(run(feed(canceled), at("08:01:00")).isEmpty());
        assertTrue(run(feed(t1(0, null, "1", null)), at("08:10:00")).isEmpty());
    }

    @Test
    void duplicateTripKeepsTheNewestUpdate() {
        RtFeed f = feed(t1(0, null, "old", at("07:59:00")), t1(60, null, "new", at("08:00:30")));
        List<TrainState> out = run(f, at("08:01:00"));
        assertEquals(1, out.size());
        assertEquals(Integer.valueOf(60), out.get(0).delaySec());
    }

    @Test
    void unmatchedTripFallsBackToRoutePredecessorAndHasNoDelay() {
        RtFeed.TripUpdate added = new RtFeed.TripUpdate("9", "EXTRA-1", "X", TestNetworks.DAY, null,
                RtFeed.TripRelationship.ADDED, null, null, List.of(stu("B", at("09:02:00"), at("09:02:30"))));
        TrainState t = run(feed(added), at("09:01:00")).get(0);
        assertEquals("subway:A", t.prevStopId());
        assertEquals(40.705, t.lat(), 1e-6); // scheduled run time of segment A>B is 120 s
        assertNull(t.delaySec());
    }

    @Test
    void trainNotYetDepartedOriginWaitsThere() {
        RtFeed.TripUpdate atOrigin = new RtFeed.TripUpdate("1", "T1", "X", TestNetworks.DAY, null,
                RtFeed.TripRelationship.SCHEDULED, null, null, List.of(
                stu("A", null, at("08:00:00")), stu("B", at("08:02:00"), at("08:02:30"))));
        TrainState t = run(feed(atOrigin), at("07:58:00")).get(0);
        assertEquals("WAITING_AT_ORIGIN", t.status());
        assertEquals(40.70, t.lat(), 1e-9);
    }
}
