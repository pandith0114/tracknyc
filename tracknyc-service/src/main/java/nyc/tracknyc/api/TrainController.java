package nyc.tracknyc.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import nyc.tracknyc.app.LiveTrainService;
import nyc.tracknyc.position.TrainState;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Polling API for trains (milestone 1; the map switches to the SSE stream in milestone 2). */
@RestController
@RequestMapping("/api/trains")
public class TrainController {

    public record TrainsResponse(Instant generatedAt, int count, List<TrainState> trains) {
    }

    private final LiveTrainService live;

    public TrainController(LiveTrainService live) {
        this.live = live;
    }

    @GetMapping
    public ResponseEntity<TrainsResponse> trains(@RequestParam(required = false) String system,
                                                 @RequestParam(required = false) String route) {
        LiveTrainService.Snapshot s = live.current();
        List<TrainState> trains = s.trains().values().stream()
                .filter(t -> system == null || system.equalsIgnoreCase(t.system()))
                .filter(t -> route == null || route.equalsIgnoreCase(t.routeId()))
                .sorted(Comparator.comparing(TrainState::id))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new TrainsResponse(s.generatedAt(), trains.size(), trains));
    }
}
