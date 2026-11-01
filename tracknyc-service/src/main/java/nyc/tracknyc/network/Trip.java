package nyc.tracknyc.network;

import java.util.Arrays;

/**
 * A scheduled trip with its stop times stored as parallel arrays (compact enough to hold the
 * whole subway timetable in memory).
 */
public final class Trip {

    private final String id;
    private final String routeId;
    private final String serviceId;
    private final String headsign;
    private final int directionId;
    private final String shapeId;
    private final String[] stopIds;
    private final int[] arrivals;
    private final int[] departures;

    public Trip(String id, String routeId, String serviceId, String headsign, int directionId, String shapeId,
                String[] stopIds, int[] arrivals, int[] departures) {
        if (stopIds.length != arrivals.length || stopIds.length != departures.length) {
            throw new IllegalArgumentException("stop time arrays differ in length for trip " + id);
        }
        this.id = id;
        this.routeId = routeId;
        this.serviceId = serviceId;
        this.headsign = headsign;
        this.directionId = directionId;
        this.shapeId = shapeId == null || shapeId.isEmpty() ? null : shapeId;
        this.stopIds = stopIds;
        this.arrivals = arrivals;
        this.departures = departures;
    }

    public String id() {
        return id;
    }

    public String routeId() {
        return routeId;
    }

    public String serviceId() {
        return serviceId;
    }

    public String headsign() {
        return headsign;
    }

    public int directionId() {
        return directionId;
    }

    public String shapeId() {
        return shapeId;
    }

    public int stopCount() {
        return stopIds.length;
    }

    public String stopId(int i) {
        return stopIds[i];
    }

    public int arrival(int i) {
        return arrivals[i];
    }

    public int departure(int i) {
        return departures[i];
    }

    public int firstDeparture() {
        return departures[0];
    }

    public int lastArrival() {
        return arrivals[arrivals.length - 1];
    }

    /** Index of {@code stopId} in this trip, or -1. Trips visit a stop at most once in practice. */
    public int indexOf(String stopId) {
        for (int i = 0; i < stopIds.length; i++) {
            if (stopIds[i].equals(stopId)) {
                return i;
            }
        }
        return -1;
    }

    public String[] stopIds() {
        return Arrays.copyOf(stopIds, stopIds.length);
    }

    @Override
    public String toString() {
        return "Trip[" + id + " route=" + routeId + " stops=" + stopIds.length + "]";
    }
}
