package nyc.tracknyc.api;

import java.util.List;
import nyc.tracknyc.app.DepartureBoardService;
import nyc.tracknyc.app.DepartureBoardService.DepartureBoard;
import nyc.tracknyc.app.DepartureBoardService.NearbyStation;
import nyc.tracknyc.app.DepartureBoardService.TrainDetail;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Live departure boards, train detail and "stations near me". */
@RestController
@RequestMapping("/api")
public class StationController {

    private final DepartureBoardService boards;

    public StationController(DepartureBoardService boards) {
        this.boards = boards;
    }

    @GetMapping("/stations/{id}/departures")
    public ResponseEntity<DepartureBoard> departures(@PathVariable String id) {
        return boards.board(id).map(b -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(b))
                .orElseThrow(() -> new ApiExceptionHandler.NotFoundException("no such station: " + id));
    }

    @GetMapping("/trains/{id}")
    public ResponseEntity<TrainDetail> train(@PathVariable String id) {
        return boards.train(id).map(t -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(t))
                .orElseThrow(() -> new ApiExceptionHandler.NotFoundException("no such train: " + id));
    }

    @GetMapping("/stations/nearby")
    public List<NearbyStation> nearby(@RequestParam double lat, @RequestParam double lon,
                                      @RequestParam(defaultValue = "1500") double radius,
                                      @RequestParam(defaultValue = "8") int limit) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("lat/lon out of range");
        }
        return boards.nearbyStations(lat, lon, Math.min(radius, 10_000), Math.min(limit, 50));
    }
}
