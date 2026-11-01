package nyc.tracknyc.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nyc.live.core.feed.FeedHealthTracker;
import nyc.live.core.feed.FeedStatus;
import nyc.live.core.geo.MotionProfile;
import nyc.tracknyc.Fixtures;
import nyc.tracknyc.feeds.ReplayFeedAdapter;
import nyc.tracknyc.TestClock;
import nyc.tracknyc.matching.NyctTripMatcher;
import nyc.tracknyc.matching.TripMatcher;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.position.PositionEngine;
import nyc.tracknyc.position.PositionMode;
import nyc.tracknyc.position.TrainState;
import nyc.tracknyc.position.TrainTracker;
import org.junit.jupiter.api.Test;

/** Replay adapter -> decode -> engine, end to end, on the synthetic recordings. */
class TrainPipelineTest {

    private final SystemNetwork subway = Fixtures.network("subway");
    private final NetworkLookup lookup = new NetworkLookup() {
        final TripMatcher matcher = new NyctTripMatcher(subway);

        @Override
        public Optional<SystemNetwork> system(String id) {
            return "subway".equals(id) ? Optional.of(subway) : Optional.empty();
        }

        @Override
        public java.util.Collection<SystemNetwork> systems() {
            return java.util.List.of(subway);
        }

        @Override
        public Optional<TripMatcher> matcher(String system) {
            return "subway".equals(system) ? Optional.of(matcher) : Optional.empty();
        }
    };

    @Test
    void replayedFeedProducesLiveLookingTrains() throws Exception {
        TestClock clock = new TestClock(Instant.parse("2026-10-28T18:00:00Z"));
        FeedHealthTracker health = new FeedHealthTracker(clock, Duration.ofSeconds(60));
        health.register("subway-l", "subway", true, true);
        TrainPipeline p = new TrainPipeline(lookup, Map.of("subway-l", "subway"),
                new PositionEngine(PositionMode.INTERPOLATE, MotionProfile.LINEAR), health, new TrainTracker(), clock);
        ReplayFeedAdapter adapter = new ReplayFeedAdapter("subway-l", "subway",
                Fixtures.dir().resolve("realtime/subway-l"), Duration.ofSeconds(15), clock);

        clock.now = clock.now.plusSeconds(300);
        p.onSnapshot(adapter.fetch());
        health.recordSuccess("subway-l");
        assertEquals(FeedStatus.UP, health.health("subway-l").orElseThrow().status());

        List<TrainState> trains = p.tick();
        assertTrue(trains.size() > 10, "trains: " + trains.size());
        for (TrainState t : trains) {
            assertEquals("L", t.routeId());
            assertEquals("LIVE", t.freshness());
            // Output times are wall-clock "now-ish", not 2026-10-01.
            assertTrue(Duration.between(clock.now, t.nextArrival()).abs().toMinutes() < 30, "wall clock " + t.nextArrival());
        }

        // Ten seconds later trains in transit have moved but the snapshot is the same.
        clock.now = clock.now.plusSeconds(10);
        List<TrainState> later = p.tick();
        long moved = later.stream().filter(t -> trains.stream().anyMatch(o -> o.id().equals(t.id())
                && (o.lat() != t.lat() || o.lon() != t.lon()))).count();
        assertTrue(moved > 3, "interpolated trains keep moving between feed updates: " + moved);
    }

    @Test
    void frozenFeedMarksTrainsStale() throws Exception {
        TestClock clock = new TestClock(Instant.parse("2026-10-28T18:00:00Z"));
        FeedHealthTracker health = new FeedHealthTracker(clock, Duration.ofSeconds(60));
        health.register("subway-l", "subway", true, true);
        TrainPipeline p = new TrainPipeline(lookup, Map.of("subway-l", "subway"),
                new PositionEngine(PositionMode.NEXT_STOP, MotionProfile.LINEAR), health, new TrainTracker(), clock);
        ReplayFeedAdapter adapter = new ReplayFeedAdapter("subway-l", "subway",
                Fixtures.dir().resolve("realtime/subway-l"), Duration.ofSeconds(15), clock);
        // Snapshots 40-44 of the L recording repeat snapshot 39 (a frozen upstream).
        clock.now = clock.now.plusSeconds(44 * 30 + 10);
        p.onSnapshot(adapter.fetch());
        health.recordSuccess("subway-l");
        assertEquals(FeedStatus.STALE, health.health("subway-l").orElseThrow().status());
        assertTrue(p.tick().stream().allMatch(t -> "STALE".equals(t.freshness())));
    }
}
