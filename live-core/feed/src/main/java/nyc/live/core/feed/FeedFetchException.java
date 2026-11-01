package nyc.live.core.feed;

/** Upstream failure. {@code retryable} distinguishes timeouts/5xx from 4xx/auth problems. */
public class FeedFetchException extends Exception {

    private final boolean retryable;
    private final Integer httpStatus;

    public FeedFetchException(String message, boolean retryable, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public static FeedFetchException retryable(String message, Throwable cause) {
        return new FeedFetchException(message, true, null, cause);
    }

    public static FeedFetchException http(int status, String message) {
        boolean retry = status == 429 || status >= 500;
        return new FeedFetchException(message + " (HTTP " + status + ")", retry, status, null);
    }

    public static FeedFetchException permanent(String message, Throwable cause) {
        return new FeedFetchException(message, false, null, cause);
    }

    public boolean retryable() {
        return retryable;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
