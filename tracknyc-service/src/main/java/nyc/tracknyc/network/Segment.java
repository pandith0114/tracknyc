package nyc.tracknyc.network;

import nyc.live.core.geo.Polyline;

/**
 * Track geometry between two consecutive stops, precomputed once per static load.
 *
 * @param scheduledRunSec scheduled time from departing {@code fromStopId} to arriving at
 *                        {@code toStopId}; used when the realtime feed does not say when the
 *                        train left the previous stop (the subway feed drops passed stops)
 */
public record Segment(String fromStopId, String toStopId, String shapeId, Polyline geometry, double lengthM,
                      int scheduledRunSec) {

    public static String key(String from, String to) {
        return from + '>' + to;
    }

    public String key() {
        return key(fromStopId, toStopId);
    }
}
