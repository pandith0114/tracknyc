package nyc.tracknyc.position;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides what to do with trains whose data has gone bad, so the map never silently presents
 * frozen or impossible data as live.
 *
 * <p>Four rules, all configurable under {@code tracknyc.stale}:
 * <ol>
 *   <li><b>Fade</b> — the train's own data is older than {@code fadeAfter}: keep drawing it, marked
 *       STALE, and the client draws it dimmed.</li>
 *   <li><b>Remove</b> — older than {@code removeAfter}: drop it. A ghost that has not been
 *       mentioned in five minutes is not a train.</li>
 *   <li><b>Vanished</b> — the trip disappeared from a feed that is itself fresh. That happens
 *       constantly in the subway feed (a trip is re-timed, re-numbered, or ends), so the train is
 *       held at its last position, marked MISSING, for {@code missingGrace} and then dropped,
 *       rather than blinking out and back.</li>
 *   <li><b>Backwards predictions</b> — the engine's new position is behind the previous one on the
 *       same segment: keep the earlier position. Trains do not reverse between stations, and
 *       predictions that jump backwards would otherwise make them twitch.</li>
 * </ol>
 * Thread-safe: ticks are serialised per feed, but the state is read by the API thread.
 */
public class TrainTracker {

    private record Remembered(TrainState state, Instant lastSeen) {
    }

    private final Duration fadeAfter;
    private final Duration removeAfter;
    private final Duration missingGrace;
    private final Map<String, Map<String, Remembered>> byFeed = new ConcurrentHashMap<>();

    public TrainTracker() {
        this(Duration.ofSeconds(90), Duration.ofSeconds(300), Duration.ofSeconds(60));
    }

    public TrainTracker(Duration fadeAfter, Duration removeAfter, Duration missingGrace) {
        this.fadeAfter = fadeAfter;
        this.removeAfter = removeAfter;
        this.missingGrace = missingGrace;
    }

    /**
     * @param computed   trains the engine produced from the newest payload of this feed
     * @param wallNow    current wall-clock time
     * @param feedStale  whether the feed as a whole is stale (see {@code FeedHealthTracker})
     * @return the trains to draw, with freshness applied
     */
    public List<TrainState> update(String feedId, List<TrainState> computed, Instant wallNow, boolean feedStale) {
        Map<String, Remembered> remembered = byFeed.computeIfAbsent(feedId, k -> new HashMap<>());
        synchronized (remembered) {
            List<TrainState> out = new ArrayList<>(computed.size());
            Set<String> seen = new HashSet<>();
            for (TrainState fresh : computed) {
                seen.add(fresh.id());
                Remembered previous = remembered.get(fresh.id());
                TrainState train = previous == null ? fresh : holdIfBackwards(previous.state(), fresh);
                Duration age = ageOf(train, wallNow);
                if (age.compareTo(removeAfter) > 0) {
                    remembered.remove(train.id());
                    continue;
                }
                String freshness = feedStale || age.compareTo(fadeAfter) > 0 ? "STALE" : "LIVE";
                TrainState drawn = train.withFreshness(freshness);
                remembered.put(drawn.id(), new Remembered(drawn, wallNow));
                out.add(drawn);
            }
            // Trains that were in the previous payload but not in this one.
            remembered.entrySet().removeIf(e -> {
                if (seen.contains(e.getKey())) {
                    return false;
                }
                Duration gone = Duration.between(e.getValue().lastSeen(), wallNow);
                if (gone.compareTo(missingGrace) > 0 || ageOf(e.getValue().state(), wallNow).compareTo(removeAfter) > 0) {
                    return true;
                }
                out.add(e.getValue().state().withFreshness("MISSING"));
                return false;
            });
            return out;
        }
    }

    /** Keeps the earlier position when a new prediction would move the train backwards. */
    static TrainState holdIfBackwards(TrainState previous, TrainState fresh) {
        boolean sameSegment = java.util.Objects.equals(previous.nextStopId(), fresh.nextStopId())
                && java.util.Objects.equals(previous.prevStopId(), fresh.prevStopId());
        if (sameSegment && fresh.progress() < previous.progress() - 1e-9
                && "INTERPOLATED".equals(fresh.positionSource())) {
            return fresh.withPositionOf(previous);
        }
        return fresh;
    }

    private static Duration ageOf(TrainState t, Instant wallNow) {
        return t.dataTimestamp() == null ? Duration.ZERO : Duration.between(t.dataTimestamp(), wallNow);
    }

    /** Forgets a feed's trains (used when a feed is reconfigured or a replay loop restarts). */
    public void forget(String feedId) {
        byFeed.remove(feedId);
    }
}
