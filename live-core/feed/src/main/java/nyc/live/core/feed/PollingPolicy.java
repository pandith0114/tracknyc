package nyc.live.core.feed;

import java.time.Duration;

/**
 * Retry and circuit-breaker settings for one feed. Kept framework-free; {@link FeedPoller}
 * translates it into Resilience4j configs.
 *
 * @param maxAttempts           attempts per poll (1 = no retry)
 * @param initialBackoff        wait before the first retry
 * @param backoffMultiplier     exponential growth factor between retries
 * @param failureRateThreshold  percentage of failed polls that opens the circuit
 * @param slidingWindowSize     number of polls the failure rate is computed over
 * @param openStateWait         how long the circuit stays open before a trial poll
 * @param fetchTimeout          hard timeout for a single attempt
 */
public record PollingPolicy(
        int maxAttempts,
        Duration initialBackoff,
        double backoffMultiplier,
        float failureRateThreshold,
        int slidingWindowSize,
        Duration openStateWait,
        Duration fetchTimeout) {

    public static PollingPolicy defaults() {
        return new PollingPolicy(3, Duration.ofSeconds(1), 2.0, 50f, 6, Duration.ofSeconds(60),
                Duration.ofSeconds(10));
    }

    public PollingPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1");
        }
    }

    /** Wait before retry number {@code retry} (1-based): initial * multiplier^(retry-1). */
    public Duration backoffBeforeRetry(int retry) {
        double millis = initialBackoff.toMillis() * Math.pow(backoffMultiplier, Math.max(0, retry - 1));
        return Duration.ofMillis(Math.round(millis));
    }

    /** Worst-case time one poll can take, used to sanity-check against the poll interval. */
    public Duration worstCasePollDuration() {
        Duration total = fetchTimeout.multipliedBy(maxAttempts);
        for (int r = 1; r < maxAttempts; r++) {
            total = total.plus(backoffBeforeRetry(r));
        }
        return total;
    }
}
