package nyc.tracknyc.matching;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nyc.tracknyc.network.SystemNetwork;
import nyc.tracknyc.network.Trip;
import nyc.tracknyc.realtime.RtFeed;

/**
 * Matches NYCT realtime trips to the static schedule. Order of preference:
 * <ol>
 *   <li>same route, direction and origin time, path id equal (or absent in realtime), service active;</li>
 *   <li>same route and direction, origin within {@link #TOLERANCE_HUNDREDTHS} (2 min), service active —
 *       covers trips re-timed by the dispatcher;</li>
 *   <li>otherwise unmatched: the train is still drawn, but without a schedule there is no delay.</li>
 * </ol>
 * Trips added in real time (supplements, reroutes) are expected to stay unmatched.
 */
public final class NyctTripMatcher implements TripMatcher {

    static final int TOLERANCE_HUNDREDTHS = 200;

    private record Candidate(NyctTripId id, Trip trip) {
    }

    private final SystemNetwork network;
    private final Map<String, List<Candidate>> byRouteDirection = new HashMap<>();

    public NyctTripMatcher(SystemNetwork network) {
        this.network = network;
        for (Trip t : network.trips()) {
            NyctTripId.parse(t.id()).ifPresent(id ->
                    byRouteDirection.computeIfAbsent(id.routeDirectionKey(), k -> new ArrayList<>())
                            .add(new Candidate(id, t)));
        }
    }

    @Override
    public Optional<Trip> match(RtFeed.TripUpdate update, LocalDate serviceDate) {
        Optional<Trip> direct = network.trip(update.tripId());
        if (direct.isPresent()) {
            return direct;
        }
        Optional<NyctTripId> parsed = NyctTripId.parse(update.tripId());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        NyctTripId rt = parsed.get();
        List<Candidate> candidates = byRouteDirection.getOrDefault(rt.routeDirectionKey(), List.of());
        Candidate best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Candidate c : candidates) {
            if (serviceDate != null && !network.isServiceActive(c.trip().serviceId(), serviceDate)) {
                continue;
            }
            int dt = Math.abs(c.id().originHundredths() - rt.originHundredths());
            if (dt > TOLERANCE_HUNDREDTHS) {
                continue;
            }
            boolean pathOk = rt.pathId().isEmpty() || rt.pathId().equals(c.id().pathId());
            // Exact origin + compatible path beats everything; then closeness in time.
            int score = dt * 2 + (pathOk ? 0 : 1);
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return Optional.ofNullable(best).map(Candidate::trip);
    }
}
