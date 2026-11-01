package nyc.tracknyc.gtfs;

import nyc.tracknyc.gtfs.GtfsRows.AgencyRow;
import nyc.tracknyc.gtfs.GtfsRows.CalendarDateRow;
import nyc.tracknyc.gtfs.GtfsRows.CalendarRow;
import nyc.tracknyc.gtfs.GtfsRows.RouteRow;
import nyc.tracknyc.gtfs.GtfsRows.ShapePointRow;
import nyc.tracknyc.gtfs.GtfsRows.StopRow;
import nyc.tracknyc.gtfs.GtfsRows.StopTimeRow;
import nyc.tracknyc.gtfs.GtfsRows.TripRow;

/**
 * Receives GTFS rows in dependency order (agency, routes, stops, calendar, calendar_dates,
 * shapes, trips, stop_times). Implemented by the in-memory model builder and the PostGIS loader.
 */
public interface GtfsSink {
    default void agency(AgencyRow row) {
    }

    void route(RouteRow row);

    void stop(StopRow row);

    default void calendar(CalendarRow row) {
    }

    default void calendarDate(CalendarDateRow row) {
    }

    default void shapePoint(ShapePointRow row) {
    }

    void trip(TripRow row);

    void stopTime(StopTimeRow row);

    /** Called once after the last row. */
    default void finish() {
    }
}
