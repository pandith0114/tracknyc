package nyc.live.core.feed;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls every enabled {@link FeedAdapter} on its own interval. Each poll is wrapped in a
 * Resilience4j {@link Retry} (exponential backoff) inside a per-feed {@link CircuitBreaker}, so
 * one broken upstream can never stall or take down the others.
 */
public class FeedPoller implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FeedPoller.class);

    private final List<FeedAdapter> adapters;
    private final FeedSnapshotListener listener;
    private final FeedHealthTracker health;
    private final FeedMetrics metrics;
    private final Clock clock;
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();
    private final Map<String, CheckedSupplier<FeedSnapshot>> pipelines = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> breakerByFeed = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public FeedPoller(List<FeedAdapter> adapters, FeedSnapshotListener listener, FeedHealthTracker health,
                      MeterRegistry registry, Function<String, PollingPolicy> policyForFeed, Clock clock) {
        this.adapters = List.copyOf(adapters);
        this.listener = listener;
        this.health = health;
        this.metrics = new FeedMetrics(registry, health);
        this.clock = clock;
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(registry);
        for (FeedAdapter a : this.adapters) {
            health.register(a.id(), a.system(), a.enabled(), a.replay());
            metrics.bindFeed(a.id(), a.system());
            if (a.enabled()) {
                pipelines.put(a.id(), buildPipeline(a, policyForFeed.apply(a.id())));
            }
        }
    }

    public FeedMetrics metrics() {
        return metrics;
    }

    private CheckedSupplier<FeedSnapshot> buildPipeline(FeedAdapter adapter, PollingPolicy p) {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.slidingWindowSize())
                .minimumNumberOfCalls(Math.min(3, p.slidingWindowSize()))
                .failureRateThreshold(p.failureRateThreshold())
                .waitDurationInOpenState(p.openStateWait())
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreaker cb = breakers.circuitBreaker(adapter.id(), cbConfig);
        cb.getEventPublisher().onStateTransition(e -> {
            String to = e.getStateTransition().getToState().name();
            health.recordCircuitState(adapter.id(), to);
            log.warn("feed {} circuit breaker {}", adapter.id(), e.getStateTransition());
        });
        breakerByFeed.put(adapter.id(), cb);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(p.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(p.initialBackoff(), p.backoffMultiplier()))
                .retryOnException(t -> t instanceof FeedFetchException ffe && ffe.retryable())
                .build();
        Retry retry = retries.retry(adapter.id(), retryConfig);

        CheckedSupplier<FeedSnapshot> attempt = () -> timedFetch(adapter);
        return CircuitBreaker.decorateCheckedSupplier(cb, Retry.decorateCheckedSupplier(retry, attempt));
    }

    private FeedSnapshot timedFetch(FeedAdapter adapter) throws FeedFetchException {
        Instant start = clock.instant();
        try {
            FeedSnapshot s = adapter.fetch();
            metrics.fetchLatency(adapter.id(), "success", Duration.between(start, clock.instant()));
            return s;
        } catch (FeedFetchException e) {
            metrics.fetchLatency(adapter.id(), "error", Duration.between(start, clock.instant()));
            metrics.fetchError(adapter.id());
            throw e;
        }
    }

    /** Starts one schedule per enabled adapter. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        long enabled = adapters.stream().filter(FeedAdapter::enabled).count();
        scheduler = Executors.newScheduledThreadPool((int) Math.max(1, Math.min(enabled, 8)), r -> {
            Thread t = new Thread(r, "feed-poller");
            t.setDaemon(true);
            return t;
        });
        long stagger = 0;
        for (FeedAdapter a : adapters) {
            if (!a.enabled()) {
                log.info("feed {} ({}) is disabled — not polling", a.id(), a.system());
                continue;
            }
            scheduler.scheduleWithFixedDelay(() -> pollOnce(a.id()), stagger,
                    a.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
            stagger += 250; // spread first requests out a little
            log.info("polling feed {} ({}) every {}", a.id(), a.system(), a.pollInterval());
        }
    }

    /** Runs a single poll synchronously. Never throws; outcomes are recorded in health/metrics. */
    public void pollOnce(String feedId) {
        CheckedSupplier<FeedSnapshot> pipeline = pipelines.get(feedId);
        if (pipeline == null) {
            return;
        }
        FeedSnapshot snapshot;
        try {
            snapshot = pipeline.get();
            health.recordSuccess(feedId);
        } catch (CallNotPermittedException open) {
            health.recordFailure(feedId, "circuit open — skipping poll");
            return;
        } catch (Throwable t) {
            health.recordFailure(feedId, t.getMessage());
            log.warn("feed {} fetch failed: {}", feedId, t.toString());
            return;
        } finally {
            CircuitBreaker cb = breakerByFeed.get(feedId);
            if (cb != null) {
                health.recordCircuitState(feedId, cb.getState().name());
            }
        }
        try {
            listener.onSnapshot(snapshot);
        } catch (Exception e) {
            metrics.decodeError(feedId);
            log.warn("feed {} payload could not be processed: {}", feedId, e.toString());
        }
    }

    public CircuitBreaker.State circuitState(String feedId) {
        CircuitBreaker cb = breakerByFeed.get(feedId);
        return cb == null ? null : cb.getState();
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
