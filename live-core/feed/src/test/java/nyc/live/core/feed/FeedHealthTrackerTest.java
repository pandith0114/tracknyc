package nyc.live.core.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedHealthTrackerTest {

    private MutableClock clock;
    private FeedHealthTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-01T12:00:00Z"));
        tracker = new FeedHealthTracker(clock, Duration.ofSeconds(60));
        tracker.register("subway-l", "subway", true, false);
    }

    @Test
    void newFeedIsStaleUntilFirstData() {
        assertEquals(FeedStatus.STALE, tracker.health("subway-l").orElseThrow().status());
        assertTrue(tracker.isStale("subway-l"));
    }

    @Test
    void freshDataIsUp() {
        tracker.recordSuccess("subway-l");
        tracker.recordData("subway-l", clock.instant().minusSeconds(8));
        FeedHealth h = tracker.health("subway-l").orElseThrow();
        assertEquals(FeedStatus.UP, h.status());
        assertEquals(Long.valueOf(8), h.ageSeconds());
        assertFalse(tracker.isStale("subway-l"));
    }

    @Test
    void failuresWhileDataStillFreshAreDegraded() {
        tracker.recordSuccess("subway-l");
        tracker.recordData("subway-l", clock.instant());
        clock.advance(Duration.ofSeconds(20));
        tracker.recordFailure("subway-l", "HTTP 503");
        FeedHealth h = tracker.health("subway-l").orElseThrow();
        assertEquals(FeedStatus.DEGRADED, h.status());
        assertEquals(1, h.consecutiveFailures());
        assertEquals("HTTP 503", h.lastError());
    }

    @Test
    void oldDataIsStaleEvenIfFetchSucceeds() {
        tracker.recordSuccess("subway-l");
        // A frozen feed: fetch works but the header timestamp never moves.
        tracker.recordData("subway-l", clock.instant().minusSeconds(61));
        assertEquals(FeedStatus.STALE, tracker.health("subway-l").orElseThrow().status());
    }

    @Test
    void openCircuitIsDown() {
        tracker.recordSuccess("subway-l");
        tracker.recordData("subway-l", clock.instant());
        tracker.recordCircuitState("subway-l", "OPEN");
        assertEquals(FeedStatus.DOWN, tracker.health("subway-l").orElseThrow().status());
        tracker.recordCircuitState("subway-l", "HALF_OPEN");
        assertEquals(FeedStatus.UP, tracker.health("subway-l").orElseThrow().status());
    }

    @Test
    void neverSucceededWithFailuresIsDown() {
        tracker.recordFailure("subway-l", "connection refused");
        assertEquals(FeedStatus.DOWN, tracker.health("subway-l").orElseThrow().status());
    }

    @Test
    void disabledFeedsSayDisabled() {
        tracker.register("path", "path", false, false);
        assertEquals(FeedStatus.DISABLED, tracker.health("path").orElseThrow().status());
    }

    @Test
    void dataTimestampNeverMovesBackwardsUnlessReset() {
        Instant t = clock.instant();
        tracker.recordSuccess("subway-l");
        tracker.recordData("subway-l", t);
        tracker.recordData("subway-l", t.minusSeconds(30));
        assertEquals(t, tracker.health("subway-l").orElseThrow().dataTimestamp());
        tracker.resetData("subway-l");
        tracker.recordData("subway-l", t.minusSeconds(30));
        assertEquals(t.minusSeconds(30), tracker.health("subway-l").orElseThrow().dataTimestamp());
    }

    @Test
    void allIsSortedAndUnknownFeedsRejected() {
        tracker.register("lirr", "lirr", true, true);
        assertEquals("lirr", tracker.all().get(0).feedId());
        assertTrue(tracker.all().get(0).replay());
        assertThrows(IllegalArgumentException.class, () -> tracker.recordSuccess("nope"));
    }

    @Test
    void pollingPolicyBackoffGrowsExponentially() {
        PollingPolicy p = PollingPolicy.defaults();
        assertEquals(Duration.ofSeconds(1), p.backoffBeforeRetry(1));
        assertEquals(Duration.ofSeconds(2), p.backoffBeforeRetry(2));
        assertEquals(Duration.ofSeconds(4), p.backoffBeforeRetry(3));
        // 3 attempts x 10 s timeout + 1 s + 2 s backoff
        assertEquals(Duration.ofSeconds(33), p.worstCasePollDuration());
        assertThrows(IllegalArgumentException.class, () -> new PollingPolicy(0, Duration.ZERO, 2, 50, 5,
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
}
