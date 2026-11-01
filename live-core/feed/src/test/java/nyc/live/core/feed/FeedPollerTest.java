package nyc.live.core.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeedPollerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T12:00:00Z"));
    private final FeedHealthTracker health = new FeedHealthTracker(clock, Duration.ofSeconds(60));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** Fails the first {@code failures} calls, then succeeds. */
    static final class FlakyAdapter implements FeedAdapter {
        final AtomicInteger calls = new AtomicInteger();
        final int failures;
        final boolean retryable;

        FlakyAdapter(int failures, boolean retryable) {
            this.failures = failures;
            this.retryable = retryable;
        }

        @Override public String id() { return "flaky"; }
        @Override public String system() { return "subway"; }
        @Override public Duration pollInterval() { return Duration.ofSeconds(15); }

        @Override
        public FeedSnapshot fetch() throws FeedFetchException {
            if (calls.incrementAndGet() <= failures) {
                throw retryable ? FeedFetchException.http(503, "upstream") : FeedFetchException.http(403, "forbidden");
            }
            return FeedSnapshot.live(id(), new byte[] {1}, Instant.now());
        }
    }

    private static PollingPolicy fastPolicy() {
        return new PollingPolicy(3, Duration.ofMillis(1), 2.0, 50f, 4, Duration.ofMinutes(5), Duration.ofSeconds(1));
    }

    @Test
    void retriesTransientFailuresWithinOnePoll() {
        FlakyAdapter adapter = new FlakyAdapter(2, true);
        List<FeedSnapshot> received = new ArrayList<>();
        try (FeedPoller poller = new FeedPoller(List.of(adapter), received::add, health, registry,
                id -> fastPolicy(), clock)) {
            poller.pollOnce("flaky");
            assertEquals(3, adapter.calls.get());
            assertEquals(1, received.size());
            assertEquals(FeedStatus.STALE, health.health("flaky").orElseThrow().status()); // no data yet
            assertEquals(0, health.health("flaky").orElseThrow().consecutiveFailures());
        }
    }

    @Test
    void doesNotRetryPermanentErrors() {
        FlakyAdapter adapter = new FlakyAdapter(100, false);
        try (FeedPoller poller = new FeedPoller(List.of(adapter), s -> { }, health, registry,
                id -> fastPolicy(), clock)) {
            poller.pollOnce("flaky");
            assertEquals(1, adapter.calls.get());
            assertEquals(1, health.health("flaky").orElseThrow().consecutiveFailures());
            assertEquals(1.0, registry.get("feed.fetch.errors").tag("feed", "flaky").counter().count(), 0);
        }
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingUpstream() {
        FlakyAdapter adapter = new FlakyAdapter(1_000, true);
        try (FeedPoller poller = new FeedPoller(List.of(adapter), s -> { }, health, registry,
                id -> fastPolicy(), clock)) {
            for (int i = 0; i < 4; i++) {
                poller.pollOnce("flaky");
            }
            assertEquals(CircuitBreaker.State.OPEN, poller.circuitState("flaky"));
            int callsWhenOpened = adapter.calls.get();
            poller.pollOnce("flaky");
            assertEquals(callsWhenOpened, adapter.calls.get(), "no upstream call while open");
            assertEquals(FeedStatus.DOWN, health.health("flaky").orElseThrow().status());
        }
    }

    @Test
    void listenerFailuresCountAsDecodeErrors() {
        FlakyAdapter adapter = new FlakyAdapter(0, true);
        try (FeedPoller poller = new FeedPoller(List.of(adapter), s -> {
            throw new IllegalStateException("bad protobuf");
        }, health, registry, id -> fastPolicy(), clock)) {
            poller.pollOnce("flaky");
            assertEquals(1.0, registry.get("feed.decode.errors").tag("feed", "flaky").counter().count(), 0);
            assertTrue(registry.find("feed.fetch.latency").tag("outcome", "success").timer().count() >= 1);
        }
    }
}
