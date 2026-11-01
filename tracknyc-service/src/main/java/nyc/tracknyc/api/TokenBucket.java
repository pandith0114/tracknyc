package nyc.tracknyc.api;

/** Classic token bucket. Thread-safe; time is passed in for testability. */
public final class TokenBucket {

    private final double ratePerSecond;
    private final double capacity;
    private double tokens;
    private long lastNanos;

    public TokenBucket(double ratePerSecond, int capacity, long nowNanos) {
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    public synchronized boolean tryAcquire(long nowNanos) {
        double elapsed = Math.max(0, nowNanos - lastNanos) / 1e9;
        tokens = Math.min(capacity, tokens + elapsed * ratePerSecond);
        lastNanos = nowNanos;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    /** Seconds until the next token is available (for Retry-After). */
    public synchronized long secondsUntilToken() {
        return tokens >= 1 ? 0 : (long) Math.ceil((1 - tokens) / ratePerSecond);
    }

    public synchronized boolean isFull(long nowNanos) {
        double elapsed = Math.max(0, nowNanos - lastNanos) / 1e9;
        return tokens + elapsed * ratePerSecond >= capacity;
    }
}
