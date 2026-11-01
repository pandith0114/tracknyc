package nyc.tracknyc.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import nyc.tracknyc.Fixtures;
import nyc.tracknyc.network.Segment;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.network.Trip;
import org.junit.jupiter.api.Test;

class GtfsStaticTest {

    private static List<GtfsCsv.Row> parse(String csv) throws IOException {
        List<GtfsCsv.Row> rows = new ArrayList<>();
        GtfsCsv.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), rows::add);
        return rows;
    }

    @Test
    void csvHandlesQuotesBomCrlfAndBlankLines() throws IOException {
        List<GtfsCsv.Row> rows = parse("﻿stop_id,stop_name,stop_lat\r\n"
                + "1,\"Times Sq, 42 St\",40.75\r\n\r\n"
                + "2,\"He said \"\"hi\"\"\",40.76\n"
                + "3,\"multi\nline\",40.77");
        assertEquals(3, rows.size());
        assertEquals("1", rows.get(0).get("stop_id"));
        assertEquals("Times Sq, 42 St", rows.get(0).get("stop_name"));
        assertEquals("He said \"hi\"", rows.get(1).get("stop_name"));
        assertEquals("multi\nline", rows.get(2).get("stop_name"));
        assertEquals(40.77, rows.get(2).requireDouble("stop_lat"), 0);
        assertEquals("", rows.get(0).get("no_such_column"));
        assertThrows(GtfsFormatException.class, () -> rows.get(0).require("missing"));
    }

    @Test
    void gtfsTimesPastMidnightAndServiceDayStart() {
        assertEquals(25 * 3600 + 3 * 60 + 30, GtfsTime.parseSeconds("25:03:30"));
        assertEquals("25:03:30", GtfsTime.format(90210));
        assertThrows(GtfsFormatException.class, () -> GtfsTime.parseSeconds("8:61:00"));
        ZoneId ny = ZoneId.of("America/New_York");
        assertEquals(Instant.parse("2026-10-01T04:00:00Z"), GtfsTime.serviceDayStart(LocalDate.of(2026, 10, 1), ny));
        // DST ends 2026-11-01: "noon minus 12h" is 01:00 EDT == 05:00Z, not local midnight (04:00Z).
        assertEquals(Instant.parse("2026-11-01T05:00:00Z"), GtfsTime.serviceDayStart(LocalDate.of(2026, 11, 1), ny));
    }

    @Test
    void loadsSyntheticSubwayFixture() {
        SystemNetwork net = Fixtures.network("subway");
        assertEquals(3, net.routes().size());
        assertEquals("A7A9AC", net.route("L").orElseThrow().color());
        assertEquals("America/New_York", net.zone().getId());
        assertTrue(net.stop("L08N").isPresent());
        assertEquals("Bedford Av", net.stationOf("L08N").orElseThrow().name());
        assertEquals(List.of("L08N", "L08S"), net.platformsOf("L08").stream().sorted().toList());
        assertTrue(net.stations().stream().allMatch(s -> s.isStation()));
        assertTrue(net.isServiceActive("SYN-Daily", LocalDate.of(2026, 10, 1)));
        assertFalse(net.isServiceActive("SYN-Daily", LocalDate.of(2028, 1, 1)));

        Trip trip = net.trip("SYN26-L-Daily-00_048000_L..N01R").orElseThrow();
        assertEquals("L29N", trip.stopId(0));
        assertEquals(8 * 3600, trip.firstDeparture());
        assertEquals("8 Av", trip.headsign());

        Segment seg = net.segment("L06N", "L05N").orElseThrow();
        assertTrue(seg.lengthM() > 300 && seg.lengthM() < 600, "1 Av -> 3 Av is ~450 m: " + seg.lengthM());
        assertTrue(seg.scheduledRunSec() >= 60);
        assertEquals("L08N", net.predecessor("L", "L06N").orElseThrow());
    }

    @Test
    void segmentsFollowShapeWithEndpointsAtStops() {
        SystemNetwork net = Fixtures.network("subway");
        for (Segment s : net.segments()) {
            var a = net.stop(s.fromStopId()).orElseThrow().location();
            var b = net.stop(s.toStopId()).orElseThrow().location();
            assertTrue(s.geometry().start().distanceTo(a) < 5, "start snaps to stop " + s.key());
            assertTrue(s.geometry().end().distanceTo(b) < 5, "end snaps to stop " + s.key());
        }
    }
}
