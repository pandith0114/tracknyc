package nyc.tracknyc.network;

import nyc.live.core.geo.LatLon;

/**
 * A GTFS stop. {@code locationType} 1 is a station (parent); 0 is a platform / directional stop
 * such as the subway's "127N". Realtime feeds reference the directional children.
 */
public record Stop(String id, String name, LatLon location, int locationType, String parentId) {

    public boolean isStation() {
        return locationType == 1;
    }

    /** The id of the station this stop belongs to (itself if it is a station or has no parent). */
    public String stationId() {
        return parentId == null || parentId.isEmpty() ? id : parentId;
    }
}
