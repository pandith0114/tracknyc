package nyc.live.core.feed;

import java.time.Instant;

/**
 * Point-in-time view of a feed's health, serialised to the UI as-is.
 *
 * @param dataTimestamp wall-clock time of the newest data (feed header timestamp, corrected
 *                      for replay clock offset); null before the first successful decode
 * @param ageSeconds    seconds between {@code dataTimestamp} and the time this view was built
 */
public record FeedHealth(
        String feedId,
        String system,
        FeedStatus status,
        Instant lastSuccessAt,
        Instant lastAttemptAt,
        Instant dataTimestamp,
        Long ageSeconds,
        int consecutiveFailures,
        String lastError,
        String circuitState,
        boolean replay) {
}
