package nyc.live.core.state;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * "Latest value per entity" store. Every write carries a TTL so entities that stop being
 * updated (a feed died, a train vanished) disappear on their own instead of lingering.
 */
public interface LatestStateStore<T extends Identified> {

    void upsertAll(Collection<T> items, Duration ttl);

    void removeAll(Collection<String> ids);

    /** All entities whose TTL has not expired. */
    Map<String, T> loadAll();

    Optional<T> get(String id);

    /** Small auxiliary documents (e.g. the feed-health summary), also with a TTL. */
    void putDocument(String key, String json, Duration ttl);

    Optional<String> getDocument(String key);
}
