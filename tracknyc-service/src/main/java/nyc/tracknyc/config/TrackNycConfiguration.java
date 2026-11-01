package nyc.tracknyc.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nyc.live.core.feed.FeedAdapter;
import nyc.live.core.feed.FeedHealthTracker;
import nyc.live.core.feed.FeedPoller;
import nyc.live.core.feed.PollingPolicy;
import nyc.live.core.geo.MotionProfile;
import nyc.tracknyc.api.RateLimitFilter;
import nyc.tracknyc.app.FeedAdapterFactory;
import nyc.tracknyc.app.LiveTrainService;
import nyc.tracknyc.app.NetworkService;
import nyc.tracknyc.app.PublishingTrainService;
import nyc.tracknyc.app.TrainStatePublisher;
import nyc.tracknyc.app.TrainStreamService;
import nyc.tracknyc.app.TrainPipeline;
import nyc.tracknyc.persistence.PostgisGtfsLoader;
import nyc.tracknyc.persistence.PostgisNetworkRepository;
import nyc.tracknyc.position.PositionEngine;
import nyc.tracknyc.position.PositionMode;
import nyc.tracknyc.position.TrainTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Wires the framework-free core (live-core + engine) into Spring. */
@Configuration(proxyBeanMethods = false)
public class TrackNycConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TrackNycConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FeedHealthTracker feedHealthTracker(TrackNycProperties props, Clock clock) {
        return new FeedHealthTracker(clock, props.getStaleAfter());
    }

    @Bean
    NetworkService networkService(TrackNycProperties props, ObjectProvider<PostgisGtfsLoader> loader,
                                  ObjectProvider<PostgisNetworkRepository> repository) {
        return new NetworkService(props, loader.getIfAvailable(), repository.getIfAvailable());
    }

    @Bean
    PositionEngine positionEngine(TrackNycProperties props) {
        return new PositionEngine(PositionMode.parse(props.getPositionMode()),
                MotionProfile.parse(props.getMotionProfile()));
    }

    @Bean
    TrainTracker trainTracker(TrackNycProperties props) {
        TrackNycProperties.Stale s = props.getStale();
        return new TrainTracker(s.getFadeAfter(), s.getRemoveAfter(), s.getMissingGrace());
    }

    /** Holder so Spring does not try to collect individual FeedAdapter beans. */
    public record FeedAdapters(List<FeedAdapter> all) {
    }

    @Bean
    FeedAdapters feedAdapters(TrackNycProperties props, Clock clock) {
        return new FeedAdapters(FeedAdapterFactory.create(props, clock));
    }

    @Bean
    TrainPipeline trainPipeline(TrackNycProperties props, NetworkService networks, PositionEngine engine,
                                FeedHealthTracker health, TrainTracker tracker, Clock clock) {
        Map<String, String> systemByFeed = new LinkedHashMap<>();
        props.getFeeds().forEach(f -> systemByFeed.put(f.getId(), f.getSystem()));
        return new TrainPipeline(networks, systemByFeed, engine, health, tracker, clock);
    }

    @Bean(destroyMethod = "close")
    FeedPoller feedPoller(FeedAdapters feedAdapters, TrainPipeline pipeline, FeedHealthTracker health,
                          MeterRegistry registry, Clock clock) {
        return new FeedPoller(feedAdapters.all(), pipeline, health, registry, id -> PollingPolicy.defaults(), clock);
    }

    @Bean(destroyMethod = "close")
    LiveTrainService liveTrainService(TrackNycProperties props, TrainPipeline pipeline, MeterRegistry registry,
                                      TrainStatePublisher publisher) {
        return new PublishingTrainService(pipeline, props.getTickInterval(), registry, props.getSystems().keySet(),
                publisher);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(TrackNycProperties props) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(new RateLimitFilter(props.getRateLimit()));
        reg.addUrlPatterns("/api/*");
        return reg;
    }

    /** Startup order: static data, then feeds, then the position ticker. */
    @Bean
    Starter starter(TrackNycProperties props, NetworkService networks, FeedPoller poller, LiveTrainService live,
                    TrainStreamService stream) {
        return new Starter(props, networks, poller, live, stream);
    }

    public static class Starter {
        private final TrackNycProperties props;
        private final NetworkService networks;
        private final FeedPoller poller;
        private final LiveTrainService live;
        private final TrainStreamService stream;

        Starter(TrackNycProperties props, NetworkService networks, FeedPoller poller, LiveTrainService live,
                TrainStreamService stream) {
            this.props = props;
            this.networks = networks;
            this.poller = poller;
            this.live = live;
            this.stream = stream;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void start() {
            log.info("TrackNYC starting in {} mode (positions: {})", props.isReplay() ? "REPLAY" : "LIVE",
                    props.getPositionMode());
            networks.loadAll(false);
            if (props.getState().hasRole("push")) {
                stream.start();
            }
            if (props.getState().hasRole("ingest")) {
                poller.start();
                live.start();
            }
        }

        /** Pushes feed health to connected clients when it changes. */
        @Scheduled(fixedDelayString = "${tracknyc.push.flush-interval:5s}")
        public void publishHealth() {
            if (props.getState().hasRole("push")) {
                stream.broadcastHealth();
            }
        }

        /** Nightly static GTFS refresh (live mode only; replay fixtures never change). */
        @Scheduled(cron = "${tracknyc.static-data.refresh-cron:0 30 3 * * *}", zone = "America/New_York")
        public void nightlyRefresh() {
            if (!props.isReplay()) {
                log.info("nightly static GTFS refresh");
                networks.loadAll(true);
            }
        }
    }
}
