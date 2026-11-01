package nyc.tracknyc.position;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import nyc.tracknyc.gtfs.GtfsRows;
import nyc.tracknyc.gtfs.GtfsTime;
import nyc.tracknyc.network.NetworkModelBuilder;
import nyc.tracknyc.network.SystemNetwork;

/** A tiny hand-made network: three stations 0.01 deg apart on a north-south line. */
final class TestNetworks {

    static final ZoneId NY = ZoneId.of("America/New_York");
    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private TestNetworks() {
    }

    static Instant at(String hms) {
        return GtfsTime.at(DAY, NY, GtfsTime.parseSeconds(hms));
    }

    static SystemNetwork line() {
        NetworkModelBuilder b = new NetworkModelBuilder("subway", NY);
        b.agency(new GtfsRows.AgencyRow("A", "Test", "America/New_York"));
        b.route(new GtfsRows.RouteRow("X", "X", "Test line", 1, "FF0000", "FFFFFF"));
        b.stop(new GtfsRows.StopRow("A", "Alpha", 40.70, -74.00, 0, ""));
        b.stop(new GtfsRows.StopRow("B", "Bravo", 40.71, -74.00, 0, ""));
        b.stop(new GtfsRows.StopRow("C", "Charlie", 40.72, -74.00, 0, ""));
        b.calendar(new GtfsRows.CalendarRow("D", new boolean[] {true, true, true, true, true, true, true},
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        b.shapePoint(new GtfsRows.ShapePointRow("SH", 0, 40.70, -74.00));
        b.shapePoint(new GtfsRows.ShapePointRow("SH", 1, 40.72, -74.00));
        b.trip(new GtfsRows.TripRow("T1", "X", "D", "Charlie", 0, "SH"));
        b.stopTime(new GtfsRows.StopTimeRow("T1", 1, "A", GtfsTime.parseSeconds("08:00:00"), GtfsTime.parseSeconds("08:00:00")));
        b.stopTime(new GtfsRows.StopTimeRow("T1", 2, "B", GtfsTime.parseSeconds("08:02:00"), GtfsTime.parseSeconds("08:02:30")));
        b.stopTime(new GtfsRows.StopTimeRow("T1", 3, "C", GtfsTime.parseSeconds("08:04:30"), GtfsTime.parseSeconds("08:04:30")));
        return b.build();
    }
}
