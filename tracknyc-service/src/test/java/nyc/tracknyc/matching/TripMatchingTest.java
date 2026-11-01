package nyc.tracknyc.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import nyc.tracknyc.Fixtures;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.realtime.RtFeed;
import org.junit.jupiter.api.Test;

class TripMatchingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private final SystemNetwork net = Fixtures.network("subway");
    private final NyctTripMatcher matcher = new NyctTripMatcher(net);

    private static RtFeed.TripUpdate rt(String tripId) {
        return new RtFeed.TripUpdate("1", tripId, null, DAY, null, RtFeed.TripRelationship.SCHEDULED, null, null,
                List.of());
    }

    @Test
    void parsesNyctTripIds() {
        NyctTripId id = NyctTripId.parse("AFA23GEN-1037-Weekday-00_067600_1..S03R").orElseThrow();
        assertEquals(67600, id.originHundredths());
        assertEquals("1", id.route());
        assertEquals("S", id.direction());
        assertEquals("03R", id.pathId());
        assertEquals(11 * 3600 + 16 * 60, id.originSeconds());
        NyctTripId shortForm = NyctTripId.parse("048000_L.N").orElseThrow();
        assertEquals("", shortForm.pathId());
        assertTrue(NyctTripId.parse("not-a-trip").isEmpty());
        assertTrue(NyctTripId.parse(null).isEmpty());
    }

    @Test
    void matchesRealtimeIdWithAndWithoutPath() {
        assertEquals("SYN26-L-Daily-00_048000_L..N01R", matcher.match(rt("048000_L..N01R"), DAY).orElseThrow().id());
        assertEquals("SYN26-7-Daily-00_048000_7..N97R", matcher.match(rt("048000_7..N"), DAY).orElseThrow().id());
    }

    @Test
    void toleratesSmallOriginShiftsButNotLargeOnes() {
        // 7 runs every 5 min from 05:00, so 08:00 exists; 08:01 matches it, 08:02:30 is 2.5 min off.
        assertEquals("SYN26-7-Daily-00_048000_7..N97R", matcher.match(rt("048100_7..N"), DAY).orElseThrow().id());
        assertTrue(matcher.match(rt("048250_7..N"), DAY).isEmpty());
    }

    @Test
    void respectsServiceCalendarAndDirection() {
        assertTrue(matcher.match(rt("048000_L..N01R"), LocalDate.of(2030, 1, 1)).isEmpty());
        assertTrue(matcher.match(rt("048000_X..N"), DAY).isEmpty());
        assertEquals("S", NyctTripId.parse(matcher.match(rt("048300_L..S"), DAY).orElseThrow().id())
                .orElseThrow().direction());
    }

    @Test
    void strategyFactory() {
        assertTrue(TripMatcher.forStrategy("nyct", net) instanceof NyctTripMatcher);
        assertTrue(TripMatcher.forStrategy("exact", net) instanceof ExactTripMatcher);
    }
}
