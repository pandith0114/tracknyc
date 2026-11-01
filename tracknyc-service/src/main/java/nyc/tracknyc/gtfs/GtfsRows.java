package nyc.tracknyc.gtfs;

import java.time.LocalDate;

/** Plain records for the GTFS rows TrackNYC uses. Times are seconds since the service-day start. */
public final class GtfsRows {

    private GtfsRows() {
    }

    public record AgencyRow(String agencyId, String name, String timezone) {
    }

    public record RouteRow(String routeId, String shortName, String longName, int routeType, String color,
                           String textColor) {
    }

    public record StopRow(String stopId, String name, double lat, double lon, int locationType,
                          String parentStation) {
    }

    public record TripRow(String tripId, String routeId, String serviceId, String headsign, int directionId,
                          String shapeId) {
    }

    public record StopTimeRow(String tripId, int stopSequence, String stopId, int arrival, int departure) {
    }

    public record ShapePointRow(String shapeId, int sequence, double lat, double lon) {
    }

    /** {@code days} is Monday..Sunday. */
    public record CalendarRow(String serviceId, boolean[] days, LocalDate start, LocalDate end) {
    }

    /** exceptionType 1 = added, 2 = removed. */
    public record CalendarDateRow(String serviceId, LocalDate date, int exceptionType) {
    }
}
