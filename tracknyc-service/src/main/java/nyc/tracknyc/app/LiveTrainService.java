package nyc.tracknyc.app;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import nyc.tracknyc.position.TrainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the pipeline every tick and keeps the latest set of trains. Exposes
 * {@code tracknyc_active_trains{system}}.
 */
public class LiveTrainService implements AutoCloseable, TrainSnapshots {

    private static final Logger log = LoggerFactory.getLogger(LiveTrainService.class);

    public record Snapshot(Instant generatedAt, Map<String, TrainState> trains) {
    }

    private final TrainPipeline pipeline;
    private final Duration tickInterval;
    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(Instant.EPOCH, Map.of()));
    private ScheduledExecutorService scheduler;

    public LiveTrainService(TrainPipeline pipeline, Duration tickInterval, MeterRegistry registry,
                            Collection<String> systems) {
        this.pipeline = pipeline;
        this.tickInterval = tickInterval;
        for (String system : systems) {
            Gauge.builder("tracknyc.active.trains", this, s -> s.count(system)).tag("system", system)
                    .description("Trains currently drawn on the map").register(registry);
        }
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "position-tick");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::safeTick, 1_000, tickInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("position tick failed: {}", e.toString());
        }
    }

    /** Recomputes all trains; returns the new snapshot. */
    public Snapshot tick() {
        List<TrainState> trains = pipeline.tick();
        Map<String, TrainState> byId = new LinkedHashMap<>();
        for (TrainState t : trains) {
            byId.put(t.id(), t);
        }
        Snapshot s = new Snapshot(pipeline.now(), Map.copyOf(byId));
        current.set(s);
        onSnapshot(s);
        return s;
    }

    /** Extension point for publishing (milestone 2). */
    protected void onSnapshot(Snapshot snapshot) {
    }

    public Snapshot current() {
        return current.get();
    }

    @Override
    public Map<String, TrainState> trains() {
        return current.get().trains();
    }

    double count(String system) {
        return current.get().trains().values().stream().filter(t -> system.equals(t.system())).count();
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
