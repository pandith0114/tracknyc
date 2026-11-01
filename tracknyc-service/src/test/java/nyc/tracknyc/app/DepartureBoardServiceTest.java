package nyc.tracknyc.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nyc.tracknyc.Fixtures;
import nyc.tracknyc.TestClock;
import nyc.tracknyc.app.DepartureBoardService.Departure;
import nyc.tracknyc.app.DepartureBoardService.DepartureBoard;
import nyc.tracknyc.matching.NyctTripMatcher;
import nyc.tracknyc.matching.TripMatcher;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.realtime.GtfsRtDecoder;
import nyc.tracknyc.realtime.RtFeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Departure boards and "stations near me" built from the recorded fixtures. */
class DepartureBoardServiceTest {

    private final SystemNetwork subway = Fixtures.network("subway");
    private final TripMatcher matcher = new NyctTripMatcher(subway);
    private final NetworkLookup lookup = new NetworkLookup() {
        @Override
        public Optional<SystemNetwork> system(String id) {
            return "subway".equals(id) ? Optional.of(subway) : Optional.empty();
        }

        @Override
        public java.util.Collection<SystemNetwork> systems() {
            return List.of(subway);
        }

        @Override
        public Optional<TripMatcher> matcher(String system) {
            return "subway".equals(system) ? Optional.of(matcher) : Optional.empty();
        }
    };

    private TestClock clock;
    private DepartureBoardService boards;

    /** Minimal pipeline stand-in holding one decoded replay snapshot. */
    private TrainPipeline pipelineWith(String feedId, int snapshot, long secondsIntoLoop) throws Exception {
        RtFeed feed = GtfsRtDecoder.decode(feedId, Fixtures.realtime(feedId, snapshot));
        // The recording starts at 07:30 local on 2026-10-01; the offset maps feed time to "now".
        Instant feedNow = feed.headerTimestamp();
        Duration offset = Duration.between(clock.instant(), feedNow);
        TrainPipeline p = new TrainPipeline(lookup, Map.of(feedId, "subway"), null,
                new nyc.live.core.feed.FeedHealthTracker(clock, Duration.ofSeconds(60)), null, clock);
        p.acceptForTest(new TrainPipeline.DecodedFeed(feedId, "subway", feed, offset, clock.instant()));
        return p;
    }

    @BeforeEach
    void setUp() {
        clock = new TestClock(Instant.parse("2026-10-28T13:00:00Z"));
    }

    @Test
    void stationBoardListsUpcomingTrainsInOrder() throws Exception {
        TrainPipeline pipeline = pipelineWith("subway-l", 20, 600);
        boards = new DepartureBoardService(lookup, pipeline, java.util.Map::of);
        DepartureBoard board = boards.board("subway:L08").orElseThrow();
        assertEquals("Bedford Av", board.name());
        assertEquals("subway:L08", board.stationId());
        assertEquals(List.of("L"), board.routes());
        assertFalse(board.departures().isEmpty(), "trains are due at Bedford Av");
        assertTrue(board.departures().size() <= 24);
        assertEquals(board.departures().stream().sorted(Comparator.comparing(Departure::arrival)).toList(),
                board.departures(), "sorted by arrival");
        for (Departure d : board.departures()) {
            assertEquals("L", d.routeId());
            assertEquals("#A7A9AC", d.color());
            assertTrue(List.of("N", "S").contains(d.direction()));
            assertTrue(d.minutesAway() >= 0 && d.minutesAway() <= 60, "within the board window: " + d.minutesAway());
            // Wall-clock conversion: arrivals are near "now", not in 2026-10-01.
            assertTrue(Duration.between(clock.instant(), d.arrival()).toMinutes() <= 61);
            assertTrue(d.track() != null, "NYCT track extension decoded");
        }
        assertTrue(board.departures().stream().anyMatch(d -> "N".equals(d.direction())));
        assertTrue(board.departures().stream().anyMatch(d -> "S".equals(d.direction())));
    }

    @Test
    void platformIdResolvesToItsStation() throws Exception {
        TrainPipeline pipeline = pipelineWith("subway-l", 20, 600);
        boards = new DepartureBoardService(lookup, pipeline, java.util.Map::of);
        assertEquals(boards.board("subway:L08").orElseThrow().departures().size(),
                boards.board("subway:L08N").orElseThrow().departures().size());
        assertTrue(boards.board("subway:NOPE").isEmpty());
        assertTrue(boards.board("malformed").isEmpty());
    }

    @Test
    void nearbyStationsAreSortedByDistance() throws Exception {
        TrainPipeline pipeline = pipelineWith("subway-l", 0, 0);
        boards = new DepartureBoardService(lookup, pipeline, java.util.Map::of);
        // Near Bedford Av (approximate coordinates, as everywhere in the fixtures).
        var near = boards.nearbyStations(40.7173, -73.9567, 1500, 5);
        assertFalse(near.isEmpty());
        assertEquals("Bedford Av", near.get(0).name());
        assertTrue(near.get(0).distanceM() < 50);
        for (int i = 1; i < near.size(); i++) {
            assertTrue(near.get(i).distanceM() >= near.get(i - 1).distanceM(), "sorted by distance");
            assertTrue(near.get(i).distanceM() <= 1500);
        }
        assertTrue(near.get(0).routes().contains("L"));
        assertTrue(boards.nearbyStations(0, 0, 1500, 5).isEmpty(), "nothing near the Gulf of Guinea");
    }
}
