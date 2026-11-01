package nyc.tracknyc.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import nyc.live.core.feed.FeedAdapter;
import nyc.tracknyc.config.TrackNycProperties;
import nyc.tracknyc.feeds.HttpGtfsRtAdapter;
import nyc.tracknyc.feeds.ReplayFeedAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns {@code tracknyc.feeds} into adapters. Replay mode uses the recorded fixtures for every
 * feed that has one (feeds without a recording show as DISABLED). Live mode uses the real HTTP
 * endpoints; a feed is enabled only if it has a URL and is not switched off.
 */
public final class FeedAdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(FeedAdapterFactory.class);

    private FeedAdapterFactory() {
    }

    public static List<FeedAdapter> create(TrackNycProperties props, Clock clock) {
        List<FeedAdapter> out = new ArrayList<>();
        for (TrackNycProperties.FeedConfig f : props.getFeeds()) {
            if (props.isReplay()) {
                Path dir = f.getReplayDir() == null || f.getReplayDir().isBlank() ? null
                        : Path.of(props.getFixturesDir()).resolve(f.getReplayDir());
                if (dir != null && Files.isRegularFile(dir.resolve("manifest.properties"))) {
                    out.add(new ReplayFeedAdapter(f.getId(), f.getSystem(), dir, f.getPollInterval(), clock));
                    continue;
                }
                log.info("feed {} has no replay recording; it will show as disabled", f.getId());
                out.add(disabled(f, clock));
            } else {
                out.add(new HttpGtfsRtAdapter(f.getId(), f.getSystem(), f.getUrl(), f.getPollInterval(), f.getTimeout(),
                        f.getApiKeyHeader(), props.getMtaApiKey(), f.isEnabled(), clock));
            }
        }
        return out;
    }

    private static FeedAdapter disabled(TrackNycProperties.FeedConfig f, Clock clock) {
        return new HttpGtfsRtAdapter(f.getId(), f.getSystem(), null, f.getPollInterval(), f.getTimeout(),
                f.getApiKeyHeader(), null, false, clock);
    }
}
