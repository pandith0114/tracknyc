package nyc.live.core.feed;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * Feed-health metrics. Prometheus names (after Micrometer's naming convention):
 * <ul>
 *   <li>{@code feed_fetch_latency_seconds{feed,outcome}} — histogram of fetch time per source</li>
 *   <li>{@code feed_fetch_errors_total{feed}}</li>
 *   <li>{@code feed_decode_errors_total{feed}}</li>
 *   <li>{@code feed_data_age_seconds{feed,system}} — age of the newest data</li>
 *   <li>{@code feed_status{feed,system}} — 0 up, 1 degraded, 2 stale, 3 down, 4 disabled</li>
 * </ul>
 */
public class FeedMetrics {

    private final MeterRegistry registry;
    private final FeedHealthTracker tracker;

    public FeedMetrics(MeterRegistry registry, FeedHealthTracker tracker) {
        this.registry = registry;
        this.tracker = tracker;
    }

    public void bindFeed(String feedId, String system) {
        Gauge.builder("feed.data.age", tracker,
                        t -> t.health(feedId).map(h -> h.ageSeconds() == null ? Double.NaN : h.ageSeconds())
                                .orElse(Double.NaN))
                .tag("feed", feedId).tag("system", system)
                .baseUnit("seconds")
                .description("Age of the newest data received from the feed")
                .register(registry);
        Gauge.builder("feed.status", tracker,
                        t -> t.health(feedId).map(h -> (double) h.status().gaugeValue()).orElse(Double.NaN))
                .tag("feed", feedId).tag("system", system)
                .description("0=up 1=degraded 2=stale 3=down 4=disabled")
                .register(registry);
    }

    public void fetchLatency(String feedId, String outcome, Duration d) {
        Timer.builder("feed.fetch.latency")
                .tag("feed", feedId).tag("outcome", outcome)
                .publishPercentileHistogram()
                .description("Time to fetch one payload from the upstream feed")
                .register(registry)
                .record(d);
    }

    public void fetchError(String feedId) {
        Counter.builder("feed.fetch.errors").tag("feed", feedId).register(registry).increment();
    }

    public void decodeError(String feedId) {
        Counter.builder("feed.decode.errors").tag("feed", feedId)
                .description("Payloads that could not be decoded").register(registry).increment();
    }
}
