package nyc.tracknyc.feeds;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import nyc.live.core.feed.FeedSnapshot;
import nyc.tracknyc.Fixtures;
import nyc.tracknyc.TestClock;
import nyc.tracknyc.realtime.GtfsRtDecoder;
import nyc.tracknyc.realtime.RtFeed;
import org.junit.jupiter.api.Test;

class ReplayFeedAdapterTest {

    private final Path dir = Fixtures.dir().resolve("realtime/subway-l");

    @Test
    void playsSnapshotsAtRealTimeSpeedAndLoops() throws Exception {
        TestClock clock = new TestClock(Instant.parse("2026-10-28T18:00:00Z"));
        ReplayFeedAdapter a = new ReplayFeedAdapter("subway-l", "subway", dir, Duration.ofSeconds(15), clock);
        assertTrue(a.replay());
        assertTrue(a.synthetic());

        FeedSnapshot first = a.fetch();
        assertArrayEquals(Files.readAllBytes(dir.resolve("0000.pb")), first.payload());

        clock.now = clock.now.plusSeconds(95); // 95 s in: snapshot at offset 90 (0003.pb)
        FeedSnapshot s = a.fetch();
        assertArrayEquals(Files.readAllBytes(dir.resolve("0003.pb")), s.payload());
        RtFeed decoded = GtfsRtDecoder.decode("subway-l", s.payload());
        // The feed clock is 95 s past the recording start, so data is 5 s old in feed time.
        Instant feedNow = s.feedTime(clock.now);
        assertEquals(5, Duration.between(decoded.headerTimestamp(), feedNow).toSeconds());
        // ...and converting the header to wall-clock gives "5 seconds ago".
        assertEquals(clock.now.minusSeconds(5), s.toWallClock(decoded.headerTimestamp()));

        clock.now = clock.now.plusSeconds(3600); // one full loop later: same snapshot again
        assertArrayEquals(Files.readAllBytes(dir.resolve("0003.pb")), a.fetch().payload());
    }

    @Test
    void readsIndex() throws Exception {
        var entries = ReplayFeedAdapter.readIndex(dir.resolve("index.csv"));
        assertEquals(120, entries.size());
        assertEquals(30, entries.get(1).offsetSeconds());
        assertEquals("0001.pb", entries.get(1).file());
    }

    @Test
    void httpAdapterWithoutUrlIsDisabled() {
        HttpGtfsRtAdapter a = new HttpGtfsRtAdapter("path", "path", "", Duration.ofSeconds(30), Duration.ofSeconds(5),
                "x-api-key", null, true, Clock.systemUTC());
        assertTrue(!a.enabled());
        HttpGtfsRtAdapter b = new HttpGtfsRtAdapter("l", "subway",
                "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct%2Fgtfs-l", Duration.ofSeconds(15),
                Duration.ofSeconds(5), "x-api-key", "dummy-key", true, Clock.systemUTC());
        assertEquals("dummy-key", b.request().headers().firstValue("x-api-key").orElseThrow());
        HttpGtfsRtAdapter c = new HttpGtfsRtAdapter("l", "subway",
                "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct%2Fgtfs-l", Duration.ofSeconds(15),
                Duration.ofSeconds(5), "x-api-key", "", true, Clock.systemUTC());
        assertTrue(c.request().headers().firstValue("x-api-key").isEmpty(), "no key header when no key");
    }
}
