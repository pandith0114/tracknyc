package nyc.live.core.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Raw payload returned by a {@link FeedAdapter}.
 *
 * @param feedId      adapter id
 * @param payload     raw bytes (protobuf, JSON, ...)
 * @param fetchedAt   wall-clock time the payload was received
 * @param clockOffset feed clock minus wall clock. Zero for live feeds. Replay adapters
 *                    play data recorded in the past and report how far behind the
 *                    recording is, so consumers can reason in the feed's own time.
 */
public record FeedSnapshot(String feedId, byte[] payload, Instant fetchedAt, Duration clockOffset) {

    public FeedSnapshot {
        Objects.requireNonNull(feedId, "feedId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        clockOffset = clockOffset == null ? Duration.ZERO : clockOffset;
    }

    public static FeedSnapshot live(String feedId, byte[] payload, Instant fetchedAt) {
        return new FeedSnapshot(feedId, payload, fetchedAt, Duration.ZERO);
    }

    /** The feed's notion of "now" for a given wall-clock instant. */
    public Instant feedTime(Instant wallClock) {
        return wallClock.plus(clockOffset);
    }

    /** Converts a timestamp expressed in feed time to wall-clock time. */
    public Instant toWallClock(Instant feedTime) {
        return feedTime.minus(clockOffset);
    }
}
