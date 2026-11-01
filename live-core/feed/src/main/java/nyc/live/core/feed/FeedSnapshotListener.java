package nyc.live.core.feed;

/**
 * Receives every successfully fetched payload. Implementations decode and normalise; they
 * report the decoded data timestamp back through {@link FeedHealthTracker#recordData} and
 * decode failures through {@link FeedMetrics#decodeError}.
 */
@FunctionalInterface
public interface FeedSnapshotListener {
    void onSnapshot(FeedSnapshot snapshot) throws Exception;
}
