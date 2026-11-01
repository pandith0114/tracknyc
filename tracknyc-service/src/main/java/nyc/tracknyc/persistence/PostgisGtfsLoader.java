package nyc.tracknyc.persistence;

import java.io.IOException;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nyc.live.core.geo.LatLon;
import nyc.live.core.geo.Wkt;
import nyc.tracknyc.gtfs.GtfsRows;
import nyc.tracknyc.gtfs.GtfsSink;
import nyc.tracknyc.gtfs.GtfsSource;
import nyc.tracknyc.gtfs.GtfsStaticReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads one system's static GTFS into PostGIS in a single transaction: old rows for the system
 * are deleted and the new feed inserted with JDBC batches, so readers never see a half-loaded
 * timetable. Segments are rebuilt afterwards in SQL (see {@link #rebuildSegments(String)}).
 */
public class PostgisGtfsLoader {

    private static final Logger log = LoggerFactory.getLogger(PostgisGtfsLoader.class);
    static final int BATCH = 5_000;
    static final List<String> TABLES = List.of("gtfs_stop_time", "gtfs_trip", "gtfs_calendar_date", "gtfs_calendar",
            "gtfs_shape", "gtfs_stop", "gtfs_route");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgisGtfsLoader(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Fingerprint of the feed currently loaded for {@code system}, or null. */
    public String loadedFingerprint(String system) {
        List<String> fp = jdbc.queryForList("SELECT fingerprint FROM gtfs_feed_version WHERE system = ?",
                String.class, system);
        return fp.isEmpty() ? null : fp.get(0);
    }

    /** Loads the feed unless the same fingerprint is already present. Returns true if it loaded. */
    public boolean loadIfChanged(String system, GtfsSource source, String timezone) {
        String fp = source.fingerprint();
        if (fp.equals(loadedFingerprint(system))) {
            log.info("static GTFS for {} unchanged ({}), skipping load", system, fp.substring(0, 12));
            return false;
        }
        long t0 = System.currentTimeMillis();
        int[] counts = tx.execute(status -> {
            for (String table : TABLES) {
                jdbc.update("DELETE FROM " + table + " WHERE system = ?", system);
            }
            BatchSink sink = new BatchSink(system);
            try {
                GtfsStaticReader.read(source, sink);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read GTFS for " + system, e);
            }
            jdbc.update("""
                    INSERT INTO gtfs_feed_version (system, fingerprint, source, timezone, stop_count, trip_count, loaded_at)
                    VALUES (?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (system) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, source = EXCLUDED.source,
                      timezone = EXCLUDED.timezone, stop_count = EXCLUDED.stop_count,
                      trip_count = EXCLUDED.trip_count, loaded_at = now()
                    """, system, fp, source.path().toString(), sink.timezone == null ? timezone : sink.timezone,
                    sink.stops, sink.trips);
            rebuildSegments(system);
            return new int[] {sink.stops, sink.trips, sink.stopTimes};
        });
        log.info("loaded static GTFS for {}: {} stops, {} trips, {} stop_times in {} ms", system, counts[0],
                counts[1], counts[2], System.currentTimeMillis() - t0);
        return true;
    }

    /** Hook for the segment table (milestone 2); a no-op until the V2 migration exists. */
    public void rebuildSegments(String system) {
        Integer fn = jdbc.queryForObject(
                "SELECT count(*) FROM pg_proc WHERE proname = 'tracknyc_rebuild_segments'", Integer.class);
        if (fn != null && fn > 0) {
            jdbc.queryForObject("SELECT tracknyc_rebuild_segments(?)", Integer.class, system);
        }
    }

    /** GtfsSink that buffers rows and writes them in JDBC batches. */
    final class BatchSink implements GtfsSink {
        private final String system;
        private final List<Object[]> routes = new ArrayList<>();
        private final List<Object[]> stopsBuf = new ArrayList<>();
        private final List<Object[]> calendars = new ArrayList<>();
        private final List<Object[]> calendarDates = new ArrayList<>();
        private final Map<String, List<GtfsRows.ShapePointRow>> shapes = new HashMap<>();
        private final List<Object[]> tripsBuf = new ArrayList<>();
        private final List<Object[]> stopTimesBuf = new ArrayList<>();
        private boolean shapesFlushed;
        String timezone;
        int stops;
        int trips;
        int stopTimes;

        BatchSink(String system) {
            this.system = system;
        }

        @Override
        public void agency(GtfsRows.AgencyRow row) {
            if (timezone == null && row.timezone() != null && !row.timezone().isBlank()) {
                timezone = row.timezone();
            }
        }

        @Override
        public void route(GtfsRows.RouteRow r) {
            routes.add(new Object[] {system, r.routeId(), r.shortName(), r.longName(), r.routeType(),
                    color(r.color(), "808183"), color(r.textColor(), "FFFFFF")});
        }

        private static String color(String c, String dflt) {
            String s = c == null ? "" : c.replace("#", "");
            return s.matches("[0-9A-Fa-f]{6}") ? s.toUpperCase() : dflt;
        }

        @Override
        public void stop(GtfsRows.StopRow s) {
            if (!routes.isEmpty()) {
                flush("INSERT INTO gtfs_route VALUES (?, ?, ?, ?, ?, ?, ?)", routes);
            }
            stopsBuf.add(new Object[] {system, s.stopId(), s.name(), s.locationType(),
                    s.parentStation() == null || s.parentStation().isBlank() ? null : s.parentStation(),
                    Wkt.point(LatLon.of(s.lat(), s.lon()))});
            stops++;
            if (stopsBuf.size() >= BATCH) {
                flushStops();
            }
        }

        private void flushStops() {
            flush("INSERT INTO gtfs_stop VALUES (?, ?, ?, ?, ?, ST_GeomFromText(?, 4326))", stopsBuf);
        }

        @Override
        public void calendar(GtfsRows.CalendarRow c) {
            boolean[] d = c.days();
            calendars.add(new Object[] {system, c.serviceId(), d[0], d[1], d[2], d[3], d[4], d[5], d[6],
                    Date.valueOf(c.start()), Date.valueOf(c.end())});
        }

        @Override
        public void calendarDate(GtfsRows.CalendarDateRow c) {
            calendarDates.add(new Object[] {system, c.serviceId(), Date.valueOf(c.date()), c.exceptionType()});
        }

        @Override
        public void shapePoint(GtfsRows.ShapePointRow row) {
            shapes.computeIfAbsent(row.shapeId(), k -> new ArrayList<>()).add(row);
        }

        private void flushShapes() {
            if (shapesFlushed) {
                return;
            }
            shapesFlushed = true;
            flushStops();
            flush("INSERT INTO gtfs_route VALUES (?, ?, ?, ?, ?, ?, ?)", routes);
            flush("INSERT INTO gtfs_calendar VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", calendars);
            flush("INSERT INTO gtfs_calendar_date VALUES (?, ?, ?, ?)", calendarDates);
            List<Object[]> rows = new ArrayList<>();
            shapes.forEach((id, pts) -> {
                pts.sort(Comparator.comparingInt(GtfsRows.ShapePointRow::sequence));
                if (pts.size() < 2) {
                    return;
                }
                List<LatLon> ll = new ArrayList<>(pts.size());
                pts.forEach(p -> ll.add(LatLon.of(p.lat(), p.lon())));
                rows.add(new Object[] {system, id, Wkt.lineString(ll)});
            });
            flush("INSERT INTO gtfs_shape VALUES (?, ?, ST_GeomFromText(?, 4326))", rows);
            shapes.clear();
        }

        @Override
        public void trip(GtfsRows.TripRow t) {
            flushShapes();
            tripsBuf.add(new Object[] {system, t.tripId(), t.routeId(), t.serviceId(), t.headsign(), t.directionId(),
                    t.shapeId() == null || t.shapeId().isBlank() ? null : t.shapeId()});
            trips++;
            if (tripsBuf.size() >= BATCH) {
                flush("INSERT INTO gtfs_trip VALUES (?, ?, ?, ?, ?, ?, ?)", tripsBuf);
            }
        }

        @Override
        public void stopTime(GtfsRows.StopTimeRow st) {
            if (!tripsBuf.isEmpty()) {
                flush("INSERT INTO gtfs_trip VALUES (?, ?, ?, ?, ?, ?, ?)", tripsBuf);
            }
            stopTimesBuf.add(new Object[] {system, st.tripId(), st.stopSequence(), st.stopId(), st.arrival(),
                    st.departure()});
            stopTimes++;
            if (stopTimesBuf.size() >= BATCH) {
                flush("INSERT INTO gtfs_stop_time VALUES (?, ?, ?, ?, ?, ?)", stopTimesBuf);
            }
        }

        @Override
        public void finish() {
            flushShapes();
            flush("INSERT INTO gtfs_trip VALUES (?, ?, ?, ?, ?, ?, ?)", tripsBuf);
            flush("INSERT INTO gtfs_stop_time VALUES (?, ?, ?, ?, ?, ?)", stopTimesBuf);
        }

        private void flush(String sql, List<Object[]> rows) {
            if (rows.isEmpty()) {
                return;
            }
            jdbc.batchUpdate(sql, rows);
            rows.clear();
        }
    }
}
