package nyc.tracknyc.position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrainTrackerTest {

    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");

    private static TrainState train(String id, double progress, Instant dataTs) {
        return new TrainState("subway:" + id, "subway", "subway-l", "L", id, null, "N", "8 Av",
                40.70 + progress * 0.01, -74.0, 0, 9, 0, "IN_TRANSIT", "INTERPOLATED", "subway:A", "subway:B",
                "Bravo", T0.plusSeconds(120), progress, dataTs, "LIVE");
    }

    private final TrainTracker tracker = new TrainTracker(Duration.ofSeconds(90), Duration.ofSeconds(300),
            Duration.ofSeconds(60));

    @Test
    void freshTrainsStayLive() {
        List<TrainState> out = tracker.update("subway-l", List.of(train("a", 0.2, T0)), T0, false);
        assertEquals(1, out.size());
        assertEquals("LIVE", out.get(0).freshness());
    }

    @Test
    void stillDrawnButFadedWhenItsOwnDataGoesOld() {
        List<TrainState> out = tracker.update("subway-l", List.of(train("a", 0.2, T0.minusSeconds(120))), T0, false);
        assertEquals("STALE", out.get(0).freshness());
    }

    @Test
    void droppedWhenTheDataIsAncient() {
        assertTrue(tracker.update("subway-l", List.of(train("a", 0.2, T0.minusSeconds(400))), T0, false).isEmpty());
    }

    @Test
    void aStaleFeedFadesAllItsTrains() {
        List<TrainState> out = tracker.update("subway-l", List.of(train("a", 0.2, T0)), T0, true);
        assertEquals("STALE", out.get(0).freshness());
    }

    @Test
    void vanishedTrainIsHeldThenDropped() {
        tracker.update("subway-l", List.of(train("a", 0.2, T0), train("b", 0.5, T0)), T0, false);
        // "a" disappears from the feed: held at its last position, marked MISSING.
        List<TrainState> held = tracker.update("subway-l", List.of(train("b", 0.6, T0.plusSeconds(30))),
                T0.plusSeconds(30), false);
        assertEquals(2, held.size());
        TrainState missing = held.stream().filter(t -> t.id().equals("subway:a")).findFirst().orElseThrow();
        assertEquals("MISSING", missing.freshness());
        assertEquals(0.2, missing.progress(), 1e-9, "held at its last known position");
        // After the grace period it is gone.
        List<TrainState> later = tracker.update("subway-l", List.of(train("b", 0.9, T0.plusSeconds(90))),
                T0.plusSeconds(90), false);
        assertEquals(1, later.size());
        assertEquals("subway:b", later.get(0).id());
    }

    @Test
    void backwardsPredictionsDoNotMoveTheTrainBackwards() {
        tracker.update("subway-l", List.of(train("a", 0.6, T0)), T0, false);
        TrainState jumped = tracker.update("subway-l", List.of(train("a", 0.3, T0.plusSeconds(15))),
                T0.plusSeconds(15), false).get(0);
        assertEquals(0.6, jumped.progress(), 1e-9, "position held");
        assertEquals(T0.plusSeconds(15), jumped.dataTimestamp(), "but the newer data timestamp is kept");
        // Forward motion is applied normally.
        assertEquals(0.75, tracker.update("subway-l", List.of(train("a", 0.75, T0.plusSeconds(30))),
                T0.plusSeconds(30), false).get(0).progress(), 1e-9);
    }

    @Test
    void movingToTheNextSegmentIsNotBackwards() {
        tracker.update("subway-l", List.of(train("a", 0.95, T0)), T0, false);
        TrainState next = new TrainState("subway:a", "subway", "subway-l", "L", "a", null, "N", "8 Av", 40.72,
                -74.0, 0, 9, 0, "IN_TRANSIT", "INTERPOLATED", "subway:B", "subway:C", "Charlie",
                T0.plusSeconds(200), 0.05, T0.plusSeconds(30), "LIVE");
        assertEquals(0.05, tracker.update("subway-l", List.of(next), T0.plusSeconds(30), false).get(0).progress(), 1e-9);
    }

    @Test
    void feedsAreIndependent() {
        tracker.update("subway-l", List.of(train("a", 0.2, T0)), T0, false);
        List<TrainState> other = tracker.update("lirr", List.of(train("z", 0.4, T0)), T0, true);
        assertEquals(1, other.size());
        assertEquals("STALE", other.get(0).freshness());
        assertEquals("LIVE", tracker.update("subway-l", List.of(train("a", 0.3, T0)), T0, false).get(0).freshness());
    }
}
