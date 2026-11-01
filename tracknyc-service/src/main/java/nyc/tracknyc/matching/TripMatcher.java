package nyc.tracknyc.matching;

import java.time.LocalDate;
import java.util.Optional;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.network.Trip;
import nyc.tracknyc.realtime.RtFeed;

/**
 * Finds the scheduled trip a realtime trip update refers to. Getting this right is what makes
 * delay numbers meaningful, and different agencies need different rules.
 */
public interface TripMatcher {

    Optional<Trip> match(RtFeed.TripUpdate update, LocalDate serviceDate);

    /** Strategy names used in configuration. */
    static TripMatcher forStrategy(String strategy, SystemNetwork network) {
        return switch (strategy == null ? "exact" : strategy.toLowerCase()) {
            case "nyct" -> new NyctTripMatcher(network);
            case "exact" -> new ExactTripMatcher(network);
            default -> throw new IllegalArgumentException("unknown trip matching strategy: " + strategy);
        };
    }
}
