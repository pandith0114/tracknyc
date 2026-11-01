package nyc.tracknyc.gtfs;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** GTFS time helpers. GTFS times may exceed 24:00:00 for trips running past midnight. */
public final class GtfsTime {

    public static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    private GtfsTime() {
    }

    /** "25:03:30" -> 90210 seconds after the service day's reference time. */
    public static int parseSeconds(String hms) {
        String s = hms.trim();
        int a = s.indexOf(':');
        int b = s.indexOf(':', a + 1);
        if (a < 0 || b < 0) {
            throw new GtfsFormatException("bad GTFS time '" + hms + "'");
        }
        try {
            int h = Integer.parseInt(s.substring(0, a));
            int m = Integer.parseInt(s.substring(a + 1, b));
            int sec = Integer.parseInt(s.substring(b + 1));
            if (m > 59 || sec > 59 || h < 0) {
                throw new GtfsFormatException("bad GTFS time '" + hms + "'");
            }
            return h * 3600 + m * 60 + sec;
        } catch (NumberFormatException e) {
            throw new GtfsFormatException("bad GTFS time '" + hms + "'");
        }
    }

    public static String format(int seconds) {
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /**
     * Reference instant of a service day: "noon minus 12 hours" in the agency time zone, as the
     * GTFS spec defines it (this is midnight except on daylight-saving change days).
     */
    public static Instant serviceDayStart(LocalDate serviceDate, ZoneId zone) {
        return ZonedDateTime.of(serviceDate, LocalTime.NOON, zone).minusHours(12).toInstant();
    }

    public static Instant at(LocalDate serviceDate, ZoneId zone, int seconds) {
        return serviceDayStart(serviceDate, zone).plusSeconds(seconds);
    }

    public static LocalDate parseDate(String yyyymmdd) {
        return LocalDate.parse(yyyymmdd.trim(), DATE);
    }
}
