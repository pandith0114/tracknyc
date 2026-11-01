package nyc.tracknyc.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import nyc.live.core.feed.FeedHealthTracker;
import nyc.live.core.push.Diff;
import nyc.live.core.push.SsePushGateway;
import nyc.live.core.state.MessageBus;
import nyc.tracknyc.api.HealthController.FeedsHealthResponse;
import nyc.tracknyc.config.TrackNycProperties;
import nyc.tracknyc.position.TrainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Push side of the fan-out: subscribes to the {@code positions} channel, feeds diffs into the SSE
 * gateway and hands out emitters. On start it warms the hub from the latest state in the store, so
 * the first client to connect gets a full map even if no diff has arrived yet.
 *
 * <p>Feed health is pushed as its own SSE event whenever it changes, so the UI's badge and stale
 * banner update without polling.
 */
public class TrainStreamService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TrainStreamService.class);
    private static final TypeReference<Diff<TrainState>> DIFF_TYPE = new TypeReference<>() {
    };

    private final SsePushGateway<TrainState> gateway;
    private final MessageBus bus;
    private final ObjectMapper mapper;
    private final TrainStatePublisher publisher;
    private final FeedHealthTracker health;
    private final TrackNycProperties props;
    private AutoCloseable subscription;
    private String lastHealthJson;

    public TrainStreamService(SsePushGateway<TrainState> gateway, MessageBus bus, ObjectMapper mapper,
                              TrainStatePublisher publisher, FeedHealthTracker health, TrackNycProperties props) {
        this.gateway = gateway;
        this.bus = bus;
        this.mapper = mapper;
        this.publisher = publisher;
        this.health = health;
        this.props = props;
    }

    public void start() {
        Map<String, TrainState> warm = publisher.loadAll();
        if (!warm.isEmpty()) {
            gateway.replaceAll(warm, Instant.now());
            log.info("warmed push gateway with {} trains from the state store", warm.size());
        }
        subscription = bus.subscribe(TrainStatePublisher.POSITIONS_CHANNEL, this::onDiff);
        gateway.start();
        broadcastHealth();
    }

    void onDiff(String json) {
        try {
            gateway.offer(mapper.readValue(json, DIFF_TYPE));
        } catch (Exception e) {
            log.warn("cannot read published diff: {}", e.toString());
        }
    }

    /** Sends feed health to connected clients when it changes (called on the position tick). */
    public void broadcastHealth() {
        FeedsHealthResponse response = new FeedsHealthResponse(Instant.now(), props.isReplay() ? "replay" : "live",
                props.getStaleAfter().toSeconds(), health.all());
        try {
            String json = mapper.writeValueAsString(response.feeds());
            if (!json.equals(lastHealthJson)) {
                lastHealthJson = json;
                gateway.broadcast("health", response);
            }
        } catch (Exception e) {
            log.debug("cannot serialise feed health: {}", e.toString());
        }
    }

    public SseEmitter connect() {
        return gateway.connect();
    }

    public SsePushGateway<TrainState> gateway() {
        return gateway;
    }

    @Override
    public void close() throws Exception {
        if (subscription != null) {
            subscription.close();
        }
        gateway.close();
    }
}
