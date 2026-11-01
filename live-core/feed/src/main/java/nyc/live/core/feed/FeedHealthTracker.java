package nyc.live.core.feed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe bookkeeping of fetch/decode outcomes per feed, and the rules that turn them into
 * a {@link FeedStatus}. Pure Java so the rules are unit-testable without Spring or Micrometer.
 */
public class FeedHealthTracker {

    private final Clock clock;
    private final Duration staleAfter;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * @param staleAfter data older than this is reported as {@link FeedStatus#STALE}. NJ Transit-
     *                   style terms ask for a warning after one minute, so 60 s is a sane default.
     */
    public FeedHealthTracker(Clock clock, Duration staleAfter) {
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    public void register(String feedId, String system, boolean enabled, boolean replay) {
        states.computeIfAbsent(feedId, id -> new State(id, system, enabled, replay));
    }

    public void recordSuccess(String feedId) {
        State s = state(feedId);
        synchronized (s) {
            Instant now = clock.instant();
            s.lastAttemptAt = now;
            s.lastSuccessAt = now;
            s.consecutiveFailures = 0;
            s.lastError = null;
        }
    }

    public void recordFailure(String feedId, String error) {
        State s = state(feedId);
        synchronized (s) {
            s.lastAttemptAt = clock.instant();
            s.consecutiveFailures++;
            s.lastError = error;
        }
    }

    /** Called after a payload was decoded; {@code dataTimestamp} must already be wall-clock. */
    public void recordData(String feedId, Instant dataTimestamp) {
        State s = state(feedId);
        synchronized (s) {
            if (s.dataTimestamp == null || !dataTimestamp.isBefore(s.dataTimestamp)) {
                s.dataTimestamp = dataTimestamp;
            }
        }
    }

    /** Replay loops restart the recording; allow the data timestamp to move backwards once. */
    public void resetData(String feedId) {
        State s = state(feedId);
        synchronized (s) {
            s.dataTimestamp = null;
        }
    }

    public void recordCircuitState(String feedId, String circuitState) {
        State s = state(feedId);
        synchronized (s) {
            s.circuitState = circuitState;
        }
    }

    public Optional<FeedHealth> health(String feedId) {
        State s = states.get(feedId);
        return s == null ? Optional.empty() : Optional.of(view(s, clock.instant()));
    }

    public List<FeedHealth> all() {
        Instant now = clock.instant();
        List<FeedHealth> out = new ArrayList<>();
        for (State s : states.values()) {
            out.add(view(s, now));
        }
        out.sort(Comparator.comparing(FeedHealth::system).thenComparing(FeedHealth::feedId));
        return out;
    }

    /** True if any data for the feed is older than the stale threshold (or missing). */
    public boolean isStale(String feedId) {
        return health(feedId).map(h -> h.status() == FeedStatus.STALE || h.status() == FeedStatus.DOWN)
                .orElse(true);
    }

    private State state(String feedId) {
        State s = states.get(feedId);
        if (s == null) {
            throw new IllegalArgumentException("unknown feed " + feedId + " (register it first)");
        }
        return s;
    }

    private FeedHealth view(State s, Instant now) {
        synchronized (s) {
            Long age = s.dataTimestamp == null ? null
                    : Math.max(0, Duration.between(s.dataTimestamp, now).toSeconds());
            return new FeedHealth(s.feedId, s.system, statusOf(s, age), s.lastSuccessAt, s.lastAttemptAt,
                    s.dataTimestamp, age, s.consecutiveFailures, s.lastError, s.circuitState, s.replay);
        }
    }

    FeedStatus statusOf(State s, Long ageSeconds) {
        if (!s.enabled) {
            return FeedStatus.DISABLED;
        }
        if ("OPEN".equals(s.circuitState) || "FORCED_OPEN".equals(s.circuitState)) {
            return FeedStatus.DOWN;
        }
        if (s.lastSuccessAt == null || ageSeconds == null) {
            return s.consecutiveFailures > 0 ? FeedStatus.DOWN : FeedStatus.STALE;
        }
        if (ageSeconds > staleAfter.toSeconds()) {
            return FeedStatus.STALE;
        }
        return s.consecutiveFailures > 0 ? FeedStatus.DEGRADED : FeedStatus.UP;
    }

    static final class State {
        final String feedId;
        final String system;
        final boolean enabled;
        final boolean replay;
        Instant lastSuccessAt;
        Instant lastAttemptAt;
        Instant dataTimestamp;
        int consecutiveFailures;
        String lastError;
        String circuitState = "CLOSED";

        State(String feedId, String system, boolean enabled, boolean replay) {
            this.feedId = feedId;
            this.system = system;
            this.enabled = enabled;
            this.replay = replay;
        }
    }
}
