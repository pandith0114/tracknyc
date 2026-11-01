package nyc.tracknyc.app;

import java.util.Optional;
import nyc.tracknyc.matching.TripMatcher;
import nyc.tracknyc.network.SystemNetwork;

/** Read access to the current static network (implemented by {@link NetworkService}). */
public interface NetworkLookup {
    Optional<SystemNetwork> system(String id);

    java.util.Collection<SystemNetwork> systems();

    Optional<TripMatcher> matcher(String system);
}
