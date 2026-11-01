package nyc.tracknyc.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import nyc.live.core.feed.FeedHealthTracker;
import nyc.live.core.feed.FeedSnapshot;
import nyc.live.core.feed.FeedSnapshotListener;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.position.PositionEngine;
import nyc.tracknyc.position.TrainState;
import nyc.tracknyc.position.TrainTracker;
import nyc.tracknyc.realtime.GtfsRtDecoder;
import nyc.tracknyc.realtime.RtFeed;

/**
 * The framework-free heart of ingest: decodes every fetched snapshot, keeps the newest decoded
 * feed per source, and on each tick turns all of them into {@link TrainState}s.
 *
 * <p>Feeds are independent: a feed that stops updating only affects its own trains (they fade
 * and then disappear per the stale rules) while every other system keeps moving.
 */
public class TrainPipeline implements FeedSnapshotListener {

    /** Latest decoded payload of one feed together with its clock offset. */
    public record DecodedFeed(String feedId, String system, RtFeed feed, Duration clockOffset, Instant receivedAt) {
        public Instant feedNow(Instant wall) {
            return wall.plus(clockOffset);
        }
    }

    private final NetworkLookup networks;
    private final Map<String, String> systemByFeed;
    private final PositionEngine engine;
    private final FeedHealthTracker health;
    private final TrainTracker tracker;
    private final Clock clock;
    private final Map<String, DecodedFeed> latest = new ConcurrentHashMap<>();

    public TrainPipeline(NetworkLookup networks, Map<String, String> systemByFeed, PositionEngine engine,
                         FeedHealthTracker health, TrainTracker tracker, Clock clock) {
        this.networks = networks;
        this.systemByFeed = Map.copyOf(systemByFeed);
        this.engine = engine;
        this.health = health;
        this.tracker = tracker;
        this.clock = clock;
    }

    /** Called by the poller for every successful fetch. Throws on undecodable payloads. */
    @Override
    public void onSnapshot(FeedSnapshot snapshot) throws Exception {
        RtFeed feed = GtfsRtDecoder.decode(snapshot.feedId(), snapshot.payload());
        String system = systemByFeed.get(snapshot.feedId());
        if (system == null) {
            throw new IllegalStateException("feed " + snapshot.feedId() + " has no system");
        }
        latest.put(snapshot.feedId(), new DecodedFeed(snapshot.feedId(), system, feed, snapshot.clockOffset(),
                snapshot.fetchedAt()));
        health.recordData(snapshot.feedId(), snapshot.toWallClock(feed.headerTimestamp()));
    }

    /** Recomputes every train from the newest data of every feed. */
    public List<TrainState> tick() {
        Instant wall = clock.instant();
        List<TrainState> out = new ArrayList<>();
        for (DecodedFeed d : latest.values()) {
            Optional<SystemNetwork> net = networks.system(d.system());
            if (net.isEmpty()) {
                continue;
            }
            List<TrainState> computed = networks.matcher(d.system())
                    .map(m -> engine.compute(net.get(), m, d.feed(), d.feedNow(wall), d.clockOffset()))
                    .orElse(List.of());
            boolean feedStale = health.isStale(d.feedId());
            out.addAll(tracker.update(d.feedId(), computed, wall, feedStale));
        }
        return out;
    }

    /** Injects a decoded feed directly; used by tests that bypass the poller. */
    public void acceptForTest(DecodedFeed decoded) {
        latest.put(decoded.feedId(), decoded);
    }

    public Optional<DecodedFeed> latest(String feedId) {
        return Optional.ofNullable(latest.get(feedId));
    }

    public Collection<DecodedFeed> latestFeeds() {
        return latest.values();
    }

    public Instant now() {
        return clock.instant();
    }
}
