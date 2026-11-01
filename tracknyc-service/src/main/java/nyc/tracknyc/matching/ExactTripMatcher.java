package nyc.tracknyc.matching;

import java.time.LocalDate;
import java.util.Optional;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.network.Trip;
import nyc.tracknyc.realtime.RtFeed;

/** trip_id in the realtime feed equals the static trip_id (LIRR, PATH and most agencies). */
public final class ExactTripMatcher implements TripMatcher {

    private final SystemNetwork network;

    public ExactTripMatcher(SystemNetwork network) {
        this.network = network;
    }

    @Override
    public Optional<Trip> match(RtFeed.TripUpdate update, LocalDate serviceDate) {
        if (update.tripId() == null || update.tripId().isEmpty()) {
            return Optional.empty();
        }
        return network.trip(update.tripId());
    }
}
