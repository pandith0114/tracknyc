package nyc.tracknyc.app;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;

/** {@link LiveTrainService} that publishes every tick to the store and the message bus. */
public class PublishingTrainService extends LiveTrainService {

    private final TrainStatePublisher publisher;

    public PublishingTrainService(TrainPipeline pipeline, Duration tickInterval, MeterRegistry registry,
                                  Collection<String> systems, TrainStatePublisher publisher) {
        super(pipeline, tickInterval, registry, systems);
        this.publisher = publisher;
    }

    @Override
    protected void onSnapshot(Snapshot snapshot) {
        publisher.publish(snapshot.generatedAt(), snapshot.trains());
    }
}
