package nyc.tracknyc.persistence;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import nyc.live.core.geo.Polyline;
import nyc.live.core.geo.Wkt;
import nyc.tracknyc.gtfs.GtfsRows;
import nyc.tracknyc.network.NetworkModelBuilder;
import nyc.tracknyc.network.Segment;
import nyc.tracknyc.network.SystemNetwork;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads a system's network back out of PostGIS into the in-memory model the position engine
 * uses. Rows are replayed into the same {@link NetworkModelBuilder} the file loader uses, so both
 * paths produce identical models; segments come from the PostGIS-built table when it exists.
 */
public class PostgisNetworkRepository {

    private final JdbcTemplate jdbc;

    public PostgisNetworkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.jdbc.setFetchSize(10_000);
    }

    public Optional<SystemNetwork> load(String system, ZoneId defaultZone) {
        List<String> fp = jdbc.queryForList("SELECT fingerprint FROM gtfs_feed_version WHERE system = ?", String.class,
                system);
        if (fp.isEmpty()) {
            return Optional.empty();
        }
        String tz = jdbc.queryForObject("SELECT timezone FROM gtfs_feed_version WHERE system = ?", String.class, system);
        NetworkModelBuilder b = new NetworkModelBuilder(system, defaultZone).fingerprint(fp.get(0));
        b.agency(new GtfsRows.AgencyRow("", "", tz));
        jdbc.query("SELECT route_id, short_name, long_name, route_type, color, text_color FROM gtfs_route WHERE system = ?",
                rs -> {
                    b.route(new GtfsRows.RouteRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            rs.getString(5), rs.getString(6)));
                }, system);
        jdbc.query("SELECT stop_id, name, ST_Y(geom), ST_X(geom), location_type, parent_station FROM gtfs_stop WHERE system = ?",
                rs -> {
                    b.stop(new GtfsRows.StopRow(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getInt(5), rs.getString(6)));
                }, system);
        jdbc.query("SELECT service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday, start_date, end_date "
                + "FROM gtfs_calendar WHERE system = ?", rs -> {
                    boolean[] days = new boolean[7];
                    for (int i = 0; i < 7; i++) {
                        days[i] = rs.getBoolean(2 + i);
                    }
                    b.calendar(new GtfsRows.CalendarRow(rs.getString(1), days, rs.getDate(9).toLocalDate(),
                            rs.getDate(10).toLocalDate()));
                }, system);
        jdbc.query("SELECT service_id, date, exception_type FROM gtfs_calendar_date WHERE system = ?", rs -> {
            b.calendarDate(new GtfsRows.CalendarDateRow(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getInt(3)));
        }, system);
        jdbc.query("SELECT shape_id, ST_AsText(geom) FROM gtfs_shape WHERE system = ?", rs -> {
            Polyline line = Wkt.parseLineString(rs.getString(2));
            String id = rs.getString(1);
            for (int i = 0; i < line.size(); i++) {
                b.shapePoint(new GtfsRows.ShapePointRow(id, i, line.points().get(i).lat(), line.points().get(i).lon()));
            }
        }, system);
        jdbc.query("SELECT trip_id, route_id, service_id, headsign, direction_id, shape_id FROM gtfs_trip WHERE system = ?",
                rs -> {
                    b.trip(new GtfsRows.TripRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getString(6)));
                }, system);
        jdbc.query("SELECT trip_id, stop_sequence, stop_id, arrival_sec, departure_sec FROM gtfs_stop_time WHERE system = ?",
                rs -> {
                    b.stopTime(new GtfsRows.StopTimeRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4),
                            rs.getInt(5)));
                }, system);
        List<Segment> segments = loadSegments(system);
        if (!segments.isEmpty()) {
            b.segments(segments);
        }
        return Optional.of(b.build());
    }

    /** Segments precomputed by PostGIS (empty before the V2 migration). */
    List<Segment> loadSegments(String system) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'segment'",
                Integer.class);
        List<Segment> out = new ArrayList<>();
        if (exists == null || exists == 0) {
            return out;
        }
        jdbc.query("SELECT from_stop_id, to_stop_id, shape_id, ST_AsText(geom), length_m, scheduled_run_sec "
                + "FROM segment WHERE system = ?", rs -> {
                    Polyline g = Wkt.parseLineString(rs.getString(4));
                    out.add(new Segment(rs.getString(1), rs.getString(2), rs.getString(3), g, rs.getDouble(5),
                            rs.getInt(6)));
                }, system);
        return out;
    }
}
