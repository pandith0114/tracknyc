package nyc.tracknyc.position;

import java.time.Instant;
import nyc.live.core.state.Identified;

/**
 * The normalised, system-independent view of one train that every adapter produces and the
 * UI consumes. All times are wall-clock. Ids are namespaced ("subway:127N").
 *
 * <p>Equality covers every field, which is what the diff engine uses to decide whether a train
 * changed since the last push — so values are rounded to stable precisions.
 *
 * @param status         AT_STATION, IN_TRANSIT or WAITING_AT_ORIGIN
 * @param positionSource GPS (vehicle reported lat/lon), INTERPOLATED (estimated along the track
 *                       shape from predicted times) or NEXT_STOP (placed at the next station)
 * @param delaySec       realtime minus scheduled arrival at the next stop; null if unmatched
 * @param progress       0..1 along the current stop-to-stop segment
 * @param dataTimestamp  when the upstream feed last said something about this train
 * @param freshness      LIVE, STALE (data old: draw faded) or MISSING (vanished from the feed)
 */
public record TrainState(
        String id,
        String system,
        String feedId,
        String routeId,
        String tripId,
        String trainId,
        String direction,
        String headsign,
        double lat,
        double lon,
        double bearing,
        double speedMps,
        Integer delaySec,
        String status,
        String positionSource,
        String prevStopId,
        String nextStopId,
        String nextStopName,
        Instant nextArrival,
        double progress,
        Instant dataTimestamp,
        String freshness) implements Identified {

    public TrainState withFreshness(String f) {
        return new TrainState(id, system, feedId, routeId, tripId, trainId, direction, headsign, lat, lon, bearing,
                speedMps, delaySec, status, positionSource, prevStopId, nextStopId, nextStopName, nextArrival,
                progress, dataTimestamp, f);
    }

    /** Same train, held at an earlier position (used when predictions jump backwards). */
    public TrainState withPositionOf(TrainState other) {
        return new TrainState(id, system, feedId, routeId, tripId, trainId, direction, headsign, other.lat, other.lon,
                other.bearing, other.speedMps, delaySec, other.status, other.positionSource, other.prevStopId,
                other.nextStopId, other.nextStopName, nextArrival, other.progress, dataTimestamp, freshness);
    }

    public static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
