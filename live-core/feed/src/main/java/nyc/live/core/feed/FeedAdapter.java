package nyc.live.core.feed;

import java.time.Duration;

/**
 * One upstream data source. Implementations only know how to fetch raw bytes; decoding,
 * normalisation, retries, circuit breaking and health tracking happen elsewhere so every
 * adapter gets them for free.
 */
public interface FeedAdapter {

    /** Stable identifier, used in metrics tags, logs and the UI (e.g. {@code subway-l}). */
    String id();

    /** The system this feed belongs to (e.g. {@code subway}, {@code lirr}). */
    String system();

    /** How often the scheduler should call {@link #fetch()}. */
    Duration pollInterval();

    /** Whether the adapter is configured (credentials present, URL set, ...). */
    default boolean enabled() {
        return true;
    }

    /** True for adapters that play recorded data instead of calling a live API. */
    default boolean replay() {
        return false;
    }

    /**
     * Fetches the latest payload. Throw {@link FeedFetchException} for upstream problems
     * (marked retryable or not); never return null.
     */
    FeedSnapshot fetch() throws FeedFetchException;
}
