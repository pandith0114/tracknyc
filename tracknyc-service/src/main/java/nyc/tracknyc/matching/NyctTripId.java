package nyc.tracknyc.matching;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The NYCT trip identifier: {@code 067600_1..S03R} means origin time 676.00 minutes after
 * midnight (11:16:00), route 1, direction S, path id 03R. Static GTFS trip ids carry the same
 * identifier after an agency prefix ({@code AFA23GEN-1037-Weekday-00_067600_1..S03R}), while the
 * realtime feed may omit the path id. The ".." separator is sometimes a single ".".
 *
 * @param originHundredths origin departure in hundredths of a minute after midnight
 */
public record NyctTripId(int originHundredths, String route, String direction, String pathId) {

    private static final Pattern P = Pattern.compile("(\\d{6})_([A-Za-z0-9]+)\\.{1,2}([NS])([A-Za-z0-9]*)$");

    /** Parses a realtime or static NYCT trip id (anything before the identifier is ignored). */
    public static Optional<NyctTripId> parse(String tripId) {
        if (tripId == null) {
            return Optional.empty();
        }
        Matcher m = P.matcher(tripId);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new NyctTripId(Integer.parseInt(m.group(1)), m.group(2), m.group(3), m.group(4)));
    }

    /** Origin time in seconds after midnight. */
    public int originSeconds() {
        return (int) Math.round(originHundredths * 60 / 100.0);
    }

    public String routeDirectionKey() {
        return route + '|' + direction;
    }
}
