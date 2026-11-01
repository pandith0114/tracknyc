package nyc.tracknyc.api;

import java.time.Instant;
import java.util.List;
import nyc.live.core.feed.FeedHealth;
import nyc.live.core.feed.FeedHealthTracker;
import nyc.tracknyc.config.TrackNycProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-feed health for the UI's "live" indicator and stale banner. */
@RestController
public class HealthController {

    public record FeedsHealthResponse(Instant generatedAt, String mode, long staleAfterSeconds, List<FeedHealth> feeds) {
    }

    private final FeedHealthTracker health;
    private final TrackNycProperties props;

    public HealthController(FeedHealthTracker health, TrackNycProperties props) {
        this.health = health;
        this.props = props;
    }

    @GetMapping("/api/feeds/health")
    public ResponseEntity<FeedsHealthResponse> feeds() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new FeedsHealthResponse(Instant.now(),
                props.isReplay() ? "replay" : "live", props.getStaleAfter().toSeconds(), health.all()));
    }
}
