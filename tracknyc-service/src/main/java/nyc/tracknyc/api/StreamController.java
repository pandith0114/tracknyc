package nyc.tracknyc.api;

import java.util.Map;
import nyc.live.core.push.SsePushGateway;
import nyc.tracknyc.app.TrainStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live stream: {@code GET /api/stream/trains} sends a {@code snapshot} event, then a
 * {@code diff} event every few seconds, plus {@code health} events when feed health changes.
 */
@RestController
public class StreamController {

    private final TrainStreamService stream;

    public StreamController(TrainStreamService stream) {
        this.stream = stream;
    }

    @GetMapping(path = "/api/stream/trains", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter trains() {
        return stream.connect();
    }

    @ExceptionHandler(SsePushGateway.GatewayFullException.class)
    ResponseEntity<Map<String, String>> full(SsePushGateway.GatewayFullException e) {
        // EventSource retries automatically; tell proxies not to cache the refusal.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "15")
                .body(Map.of("error", e.getMessage()));
    }
}
