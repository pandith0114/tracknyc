package nyc.tracknyc.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import nyc.tracknyc.config.TrackNycProperties;
import nyc.tracknyc.gtfs.GtfsSource;
import nyc.tracknyc.gtfs.GtfsStaticReader;
import nyc.tracknyc.matching.TripMatcher;
import nyc.tracknyc.network.NetworkModel;
import nyc.tracknyc.network.NetworkModelBuilder;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.persistence.PostgisGtfsLoader;
import nyc.tracknyc.persistence.PostgisNetworkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the current {@link NetworkModel}. On startup each configured system is loaded either
 * straight from its GTFS files ({@code static-data.source=file}) or through PostGIS (load if the
 * feed changed, then read back). In live mode systems with a {@code static-url} are re-downloaded
 * nightly and the model is swapped atomically.
 */
public class NetworkService implements NetworkLookup {

    private static final Logger log = LoggerFactory.getLogger(NetworkService.class);

    private final TrackNycProperties props;
    private final PostgisGtfsLoader loader;            // null in file mode
    private final PostgisNetworkRepository repository; // null in file mode
    private final AtomicReference<NetworkModel> model = new AtomicReference<>(NetworkModel.empty());
    private final Map<String, TripMatcher> matchers = new ConcurrentHashMap<>();

    public NetworkService(TrackNycProperties props, PostgisGtfsLoader loader, PostgisNetworkRepository repository) {
        this.props = props;
        this.loader = loader;
        this.repository = repository;
    }

    public NetworkModel model() {
        return model.get();
    }

    @Override
    public Optional<SystemNetwork> system(String id) {
        return model.get().system(id);
    }

    @Override
    public java.util.Collection<SystemNetwork> systems() {
        return model.get().systems();
    }

    @Override
    public Optional<TripMatcher> matcher(String system) {
        return Optional.ofNullable(matchers.get(system));
    }

    /** Loads (or reloads) every configured system. Systems that fail keep their previous network. */
    public synchronized void loadAll(boolean download) {
        List<SystemNetwork> loaded = new ArrayList<>();
        for (Map.Entry<String, TrackNycProperties.SystemConfig> e : props.getSystems().entrySet()) {
            String system = e.getKey();
            try {
                loaded.add(loadSystem(system, e.getValue(), download));
            } catch (Exception ex) {
                log.error("could not load static data for {}: {}", system, ex.toString());
                model.get().system(system).ifPresent(loaded::add);
            }
        }
        NetworkModel next = new NetworkModel(loaded);
        for (SystemNetwork net : next.systems()) {
            TrackNycProperties.SystemConfig cfg = props.getSystems().get(net.system());
            matchers.put(net.system(), TripMatcher.forStrategy(cfg == null ? "exact" : cfg.getTripMatching(), net));
        }
        model.set(next);
    }

    SystemNetwork loadSystem(String system, TrackNycProperties.SystemConfig cfg, boolean download) throws Exception {
        GtfsSource source = resolveSource(system, cfg, download);
        ZoneId zone = ZoneId.of(cfg.getTimezone());
        if (loader == null) {
            NetworkModelBuilder b = new NetworkModelBuilder(system, zone).fingerprint(source.fingerprint());
            GtfsStaticReader.read(source, b);
            SystemNetwork net = b.build();
            log.info("loaded {} from {} (in memory): {} trips, {} segments", system, source.path(),
                    net.trips().size(), net.segments().size());
            return net;
        }
        loader.loadIfChanged(system, source, cfg.getTimezone());
        SystemNetwork net = repository.load(system, zone)
                .orElseThrow(() -> new IllegalStateException("no static data in PostGIS for " + system));
        log.info("loaded {} from PostGIS: {} trips, {} segments", system, net.trips().size(), net.segments().size());
        return net;
    }

    GtfsSource resolveSource(String system, TrackNycProperties.SystemConfig cfg, boolean download) throws IOException {
        boolean live = !props.isReplay();
        if (live && cfg.getStaticUrl() != null && !cfg.getStaticUrl().isBlank()) {
            Path cached = Path.of(props.getStaticData().getDownloadDir(), system + ".zip");
            if (download || !Files.exists(cached)) {
                downloadTo(cfg.getStaticUrl(), cached);
            }
            return GtfsSource.of(cached);
        }
        Path p = Path.of(cfg.getStaticPath());
        if (!p.isAbsolute()) {
            p = Path.of(props.getFixturesDir()).resolve(p);
        }
        return GtfsSource.of(p);
    }

    static void downloadTo(String url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new IOException("GET " + url + " -> HTTP " + resp.statusCode());
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("downloaded {} -> {} ({} KB)", url, target, Files.size(target) / 1024);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
