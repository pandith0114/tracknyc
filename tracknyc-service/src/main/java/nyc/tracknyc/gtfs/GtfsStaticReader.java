package nyc.tracknyc.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import nyc.tracknyc.gtfs.GtfsRows.AgencyRow;
import nyc.tracknyc.gtfs.GtfsRows.CalendarDateRow;
import nyc.tracknyc.gtfs.GtfsRows.CalendarRow;
import nyc.tracknyc.gtfs.GtfsRows.RouteRow;
import nyc.tracknyc.gtfs.GtfsRows.ShapePointRow;
import nyc.tracknyc.gtfs.GtfsRows.StopRow;
import nyc.tracknyc.gtfs.GtfsRows.StopTimeRow;
import nyc.tracknyc.gtfs.GtfsRows.TripRow;

/** Streams a {@link GtfsSource} into a {@link GtfsSink}. */
public final class GtfsStaticReader {

    static final List<String> FILES = List.of("agency.txt", "routes.txt", "stops.txt", "calendar.txt",
            "calendar_dates.txt", "shapes.txt", "trips.txt", "stop_times.txt");

    private GtfsStaticReader() {
    }

    public static void read(GtfsSource source, GtfsSink sink) throws IOException {
        each(source, "agency.txt", false, r -> sink.agency(new AgencyRow(r.get("agency_id"), r.get("agency_name"),
                r.get("agency_timezone"))));
        each(source, "routes.txt", true, r -> sink.route(new RouteRow(r.require("route_id"),
                r.get("route_short_name"), r.get("route_long_name"), r.getInt("route_type", 1),
                r.get("route_color"), r.get("route_text_color"))));
        each(source, "stops.txt", true, r -> sink.stop(new StopRow(r.require("stop_id"), r.get("stop_name"),
                r.requireDouble("stop_lat"), r.requireDouble("stop_lon"), r.getInt("location_type", 0),
                r.get("parent_station"))));
        each(source, "calendar.txt", false, r -> {
            boolean[] days = new boolean[7];
            String[] names = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
            for (int i = 0; i < 7; i++) {
                days[i] = "1".equals(r.get(names[i]));
            }
            sink.calendar(new CalendarRow(r.require("service_id"), days, GtfsTime.parseDate(r.require("start_date")),
                    GtfsTime.parseDate(r.require("end_date"))));
        });
        each(source, "calendar_dates.txt", false, r -> sink.calendarDate(new CalendarDateRow(
                r.require("service_id"), GtfsTime.parseDate(r.require("date")), r.getInt("exception_type", 1))));
        each(source, "shapes.txt", false, r -> sink.shapePoint(new ShapePointRow(r.require("shape_id"),
                r.getInt("shape_pt_sequence", 0), r.requireDouble("shape_pt_lat"), r.requireDouble("shape_pt_lon"))));
        each(source, "trips.txt", true, r -> sink.trip(new TripRow(r.require("trip_id"), r.require("route_id"),
                r.require("service_id"), r.get("trip_headsign"), r.getInt("direction_id", 0), r.get("shape_id"))));
        each(source, "stop_times.txt", true, r -> {
            String arr = r.get("arrival_time");
            String dep = r.get("departure_time");
            if (arr.isEmpty() && dep.isEmpty()) {
                return; // untimed stop (allowed by GTFS between timepoints); skipped in this subset
            }
            int a = GtfsTime.parseSeconds(arr.isEmpty() ? dep : arr);
            int d = GtfsTime.parseSeconds(dep.isEmpty() ? arr : dep);
            sink.stopTime(new StopTimeRow(r.require("trip_id"), r.getInt("stop_sequence", 0), r.require("stop_id"), a, d));
        });
        sink.finish();
    }

    private static void each(GtfsSource source, String file, boolean required, Consumer<GtfsCsv.Row> rowFn)
            throws IOException {
        Optional<InputStream> in = source.open(file);
        if (in.isEmpty()) {
            if (required) {
                throw new GtfsFormatException("required file " + file + " missing in " + source.path());
            }
            return;
        }
        try (InputStream s = in.get()) {
            GtfsCsv.read(s, rowFn);
        }
    }
}
