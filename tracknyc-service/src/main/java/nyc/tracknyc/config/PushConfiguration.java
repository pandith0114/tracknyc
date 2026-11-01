package nyc.tracknyc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import nyc.live.core.feed.FeedHealthTracker;
import nyc.live.core.push.SsePushGateway;
import nyc.live.core.state.LatestStateStore;
import nyc.live.core.state.MessageBus;
import nyc.tracknyc.app.DepartureBoardService;
import nyc.tracknyc.app.LiveTrainService;
import nyc.tracknyc.app.NetworkService;
import nyc.tracknyc.app.TrainPipeline;
import nyc.tracknyc.app.TrainStatePublisher;
import nyc.tracknyc.app.TrainStreamService;
import nyc.tracknyc.position.TrainState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fan-out: ingest publishes diffs, the SSE gateway serves them to clients. */
@Configuration(proxyBeanMethods = false)
public class PushConfiguration {

    @Bean
    TrainStatePublisher trainStatePublisher(LatestStateStore<TrainState> store, MessageBus bus, ObjectMapper mapper,
                                            TrackNycProperties props) {
        return new TrainStatePublisher(store, bus, mapper, props.getState().getTtl());
    }

    @Bean(destroyMethod = "close")
    SsePushGateway<TrainState> trainPushGateway(ObjectMapper mapper, MeterRegistry registry, Clock clock,
                                                TrackNycProperties props) {
        TrackNycProperties.Push p = props.getPush();
        return new SsePushGateway<>("trains", mapper, registry, clock, p.getFlushInterval(), p.getHeartbeatInterval(),
                p.getEmitterTimeout(), p.getMaxClients());
    }

    @Bean(destroyMethod = "close")
    TrainStreamService trainStreamService(SsePushGateway<TrainState> gateway, MessageBus bus, ObjectMapper mapper,
                                          TrainStatePublisher publisher, FeedHealthTracker health,
                                          TrackNycProperties props) {
        return new TrainStreamService(gateway, bus, mapper, publisher, health, props);
    }

    @Bean
    DepartureBoardService departureBoardService(NetworkService networks, TrainPipeline pipeline, LiveTrainService live) {
        return new DepartureBoardService(networks, pipeline, live);
    }
}
