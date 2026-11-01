package nyc.tracknyc.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import nyc.tracknyc.app.NetworkService;
import nyc.tracknyc.config.TrackNycProperties;
import nyc.tracknyc.network.SystemNetwork;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Static network documents: systems + routes (for filters), line geometry, stations. */
@RestController
@RequestMapping("/api")
public class NetworkController {

    public record SystemView(String id, String displayName, String attribution, boolean replay,
                             double[] bounds, List<NetworkViews.RouteView> routes) {
    }

    private final NetworkService networks;
    private final TrackNycProperties props;

    public NetworkController(NetworkService networks, TrackNycProperties props) {
        this.networks = networks;
        this.props = props;
    }

    @GetMapping("/systems")
    public List<SystemView> systems() {
        List<SystemView> out = new ArrayList<>();
        for (SystemNetwork net : networks.model().systems()) {
            TrackNycProperties.SystemConfig cfg = props.getSystems().get(net.system());
            out.add(new SystemView(net.system(), cfg == null ? net.system() : cfg.getDisplayName(),
                    cfg == null ? null : cfg.getAttribution(), props.isReplay(),
                    net.bounds().map(b -> b.toLonLatArray()).orElse(null), NetworkViews.routes(net)));
        }
        return out;
    }

    /** One GeoJSON layer per system (or all systems merged). Cacheable: changes at most nightly. */
    @GetMapping("/lines")
    public ResponseEntity<Map<String, Object>> lines(@RequestParam(required = false) String system) {
        return cached(collect(system, NetworkViews::lines));
    }

    @GetMapping("/stations")
    public ResponseEntity<Map<String, Object>> stations(@RequestParam(required = false) String system) {
        return cached(collect(system, NetworkViews::stations));
    }

    private Map<String, Object> collect(String system, java.util.function.Function<SystemNetwork, Map<String, Object>> f) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (SystemNetwork net : networks.model().systems()) {
            if (system == null || system.equalsIgnoreCase(net.system())) {
                parts.add(f.apply(net));
            }
        }
        return NetworkViews.merge(parts);
    }

    private static ResponseEntity<Map<String, Object>> cached(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()).body(body);
    }
}
