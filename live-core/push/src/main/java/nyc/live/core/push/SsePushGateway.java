package nyc.live.core.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import nyc.live.core.state.Identified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events front end for a {@link PushHub}: snapshot on connect, then coalesced
 * diffs every {@code flushInterval}. Metrics (Prometheus names):
 * {@code push_connected_clients{stream}}, {@code push_lag_seconds{stream}} (producer compute
 * time to send time), {@code push_bytes_total{stream}}, {@code push_dropped_clients_total{stream,reason}}.
 */
public class SsePushGateway<T extends Identified> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SsePushGateway.class);

    private final String stream;
    private final PushHub<T> hub;
    private final Duration flushInterval;
    private final Duration heartbeatInterval;
    private final Duration emitterTimeout;
    private ScheduledExecutorService scheduler;

    public SsePushGateway(String stream, ObjectMapper mapper, MeterRegistry registry, Clock clock,
                          Duration flushInterval, Duration heartbeatInterval, Duration emitterTimeout,
                          int maxClients) {
        this.stream = stream;
        this.flushInterval = flushInterval;
        this.heartbeatInterval = heartbeatInterval;
        this.emitterTimeout = emitterTimeout;
        Timer lag = Timer.builder("push.lag").tag("stream", stream)
                .description("Time from position computation to delivery to clients")
                .publishPercentileHistogram()
                .register(registry);
        Counter bytes = Counter.builder("push.bytes").tag("stream", stream).baseUnit("bytes").register(registry);
        this.hub = new PushHub<>(o -> toJson(mapper, o), clock, maxClients, new PushHub.Listener() {
            @Override
            public void diffSent(Duration l, int size, int clients) {
                lag.record(l);
                bytes.increment((double) size * clients);
            }

            @Override
            public void clientDropped(String reason) {
                Counter.builder("push.dropped.clients").tag("stream", stream).tag("reason", reason)
                        .register(registry).increment();
            }
        });
        Gauge.builder("push.connected.clients", hub, PushHub::clientCount).tag("stream", stream)
                .description("Open SSE connections").register(registry);
    }

    private static String toJson(ObjectMapper mapper, Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public PushHub<T> hub() {
        return hub;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "push-" + stream);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::safeFlush, flushInterval.toMillis(), flushInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(hub::heartbeat, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void safeFlush() {
        try {
            hub.flush();
        } catch (RuntimeException e) {
            log.warn("push flush failed for {}: {}", stream, e.toString());
        }
    }

    public void offer(Diff<T> diff) {
        hub.offer(diff);
    }

    public void replaceAll(Map<String, T> state, Instant generatedAt) {
        hub.replaceAll(state, generatedAt);
    }

    public void broadcast(String event, Object payload) {
        hub.broadcast(event, payload);
    }

    /**
     * Opens a new SSE stream. Throws {@link GatewayFullException} when at capacity so the
     * controller can answer 503 and the client retries (EventSource does so automatically).
     */
    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(emitterTimeout.toMillis());
        EmitterClient client = new EmitterClient(emitter);
        emitter.onCompletion(() -> hub.disconnect(client));
        emitter.onTimeout(() -> hub.disconnect(client));
        emitter.onError(e -> hub.disconnect(client));
        if (!hub.connect(client)) {
            emitter.complete();
            throw new GatewayFullException(stream);
        }
        return emitter;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Thrown when {@code maxClients} connections are already open. */
    public static class GatewayFullException extends RuntimeException {
        public GatewayFullException(String stream) {
            super("push gateway '" + stream + "' is at capacity");
        }
    }

    private static final class EmitterClient implements PushHub.Client {
        private final SseEmitter emitter;

        EmitterClient(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(String event, long id, String json) throws IOException {
            emitter.send(SseEmitter.event().name(event).id(Long.toString(id)).reconnectTime(3_000).data(json));
        }

        @Override
        public void comment(String text) throws IOException {
            emitter.send(SseEmitter.event().comment(text));
        }

        @Override
        public void close() {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // already completed
            }
        }
    }
}
