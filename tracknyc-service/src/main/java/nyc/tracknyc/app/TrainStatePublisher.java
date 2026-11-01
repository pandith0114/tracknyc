package nyc.tracknyc.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nyc.live.core.push.Diff;
import nyc.live.core.push.DiffEngine;
import nyc.live.core.state.LatestStateStore;
import nyc.live.core.state.MessageBus;
import nyc.tracknyc.position.TrainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingest side of the fan-out: writes the latest state of every train to the store (with a TTL)
 * and publishes the diff to the {@code positions} channel, where every push instance picks it up.
 *
 * <p>The sequence number is monotonic per ingest process; push instances only use it to order
 * diffs and to spot gaps, and a restarted ingest simply makes clients resynchronise.
 */
public class TrainStatePublisher {

    public static final String POSITIONS_CHANNEL = "tracknyc:positions";

    private static final Logger log = LoggerFactory.getLogger(TrainStatePublisher.class);

    private final LatestStateStore<TrainState> store;
    private final MessageBus bus;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private Map<String, TrainState> previous = Map.of();
    private long seq;

    public TrainStatePublisher(LatestStateStore<TrainState> store, MessageBus bus, ObjectMapper mapper, Duration ttl) {
        this.store = store;
        this.bus = bus;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    public synchronized void publish(Instant generatedAt, Map<String, TrainState> trains) {
        Diff<TrainState> diff = DiffEngine.compute(++seq, generatedAt, previous, trains);
        previous = trains;
        if (diff.isEmpty()) {
            return;
        }
        List<TrainState> upserts = new ArrayList<>(diff.upserts());
        try {
            store.upsertAll(upserts, ttl);
            if (!diff.removes().isEmpty()) {
                store.removeAll(diff.removes());
            }
            bus.publish(POSITIONS_CHANNEL, mapper.writeValueAsString(diff));
        } catch (JsonProcessingException e) {
            log.error("cannot serialise diff {}", seq, e);
        } catch (RuntimeException e) {
            // Redis down: keep computing and serving locally; state ages out by TTL.
            log.warn("cannot publish diff {}: {}", seq, e.toString());
        }
    }

    /** Snapshot of everything currently in the store (used to warm a push instance). */
    public Map<String, TrainState> loadAll() {
        return new LinkedHashMap<>(store.loadAll());
    }

    public synchronized long seq() {
        return seq;
    }
}
