package nyc.live.core.feed;

/** Health of a feed as shown to users. Ordered from best to worst. */
public enum FeedStatus {
    /** Data is fresh and the last fetch succeeded. */
    UP,
    /** Data is still fresh but recent fetches failed (retrying). */
    DEGRADED,
    /** No new data for longer than the stale threshold; trains are drawn faded. */
    STALE,
    /** Circuit breaker open or never fetched successfully. */
    DOWN,
    /** Not configured (e.g. no credentials). */
    DISABLED;

    /** Numeric value exported as the {@code feed_status} gauge (0 = up). */
    public int gaugeValue() {
        return ordinal();
    }
}
