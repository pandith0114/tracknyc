package nyc.tracknyc.realtime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import nyc.live.core.geo.LatLon;

/**
 * Decoded GTFS-realtime feed in a protobuf-free model. All times are in the feed's own clock
 * (see {@code FeedSnapshot#clockOffset()}).
 */
public record RtFeed(String feedId, Instant headerTimestamp, List<TripUpdate> tripUpdates, List<Vehicle> vehicles) {

    public RtFeed {
        tripUpdates = List.copyOf(tripUpdates);
        vehicles = List.copyOf(vehicles);
    }

    public Optional<Vehicle> vehicleForTrip(String tripId) {
        return vehicles.stream().filter(v -> tripId.equals(v.tripId())).findFirst();
    }

    public enum TripRelationship { SCHEDULED, ADDED, UNSCHEDULED, CANCELED, REPLACEMENT, DUPLICATED, DELETED, NEW }

    public enum VehicleStatus { INCOMING_AT, STOPPED_AT, IN_TRANSIT_TO }

    /** MTA NYCT extension data for a trip (null for non-NYCT feeds). */
    public record NyctTrip(String trainId, boolean assigned, String direction) {
    }

    public record StopTimeUpdate(String stopId, Integer stopSequence, Instant arrival, Instant departure,
                                 Integer arrivalDelaySec, boolean skipped, String scheduledTrack,
                                 String actualTrack) {

        /** Arrival if present, else departure. */
        public Instant arrivalOrDeparture() {
            return arrival != null ? arrival : departure;
        }

        /** Departure if present, else arrival. */
        public Instant departureOrArrival() {
            return departure != null ? departure : arrival;
        }

        public String track() {
            return actualTrack != null ? actualTrack : scheduledTrack;
        }
    }

    public record TripUpdate(String entityId, String tripId, String routeId, LocalDate startDate, String startTime,
                             TripRelationship relationship, NyctTrip nyct, Instant timestamp,
                             List<StopTimeUpdate> stopTimeUpdates) {
        public TripUpdate {
            stopTimeUpdates = List.copyOf(stopTimeUpdates);
        }
    }

    public record Vehicle(String entityId, String tripId, String routeId, String stopId, Integer currentStopSequence,
                          VehicleStatus status, LatLon position, Float bearing, Instant timestamp) {
    }
}
