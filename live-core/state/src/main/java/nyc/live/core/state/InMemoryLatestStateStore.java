package nyc.live.core.state;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Single-process store for tests and the no-Redis dev profile. Honours TTLs lazily. */
public class InMemoryLatestStateStore<T extends Identified> implements LatestStateStore<T> {

    private record Entry<V>(V value, Instant expiresAt) {
    }

    private final Clock clock;
    private final Map<String, Entry<T>> entities = new ConcurrentHashMap<>();
    private final Map<String, Entry<String>> documents = new ConcurrentHashMap<>();

    public InMemoryLatestStateStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void upsertAll(Collection<T> items, Duration ttl) {
        Instant exp = clock.instant().plus(ttl);
        for (T item : items) {
            entities.put(item.id(), new Entry<>(item, exp));
        }
    }

    @Override
    public void removeAll(Collection<String> ids) {
        ids.forEach(entities::remove);
    }

    @Override
    public Map<String, T> loadAll() {
        Instant now = clock.instant();
        entities.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
        Map<String, T> out = new LinkedHashMap<>();
        entities.forEach((k, v) -> out.put(k, v.value()));
        return out;
    }

    @Override
    public Optional<T> get(String id) {
        Entry<T> e = entities.get(id);
        return e == null || !e.expiresAt().isAfter(clock.instant()) ? Optional.empty() : Optional.of(e.value());
    }

    @Override
    public void putDocument(String key, String json, Duration ttl) {
        documents.put(key, new Entry<>(json, clock.instant().plus(ttl)));
    }

    @Override
    public Optional<String> getDocument(String key) {
        Entry<String> e = documents.get(key);
        return e == null || !e.expiresAt().isAfter(clock.instant()) ? Optional.empty() : Optional.of(e.value());
    }
}
