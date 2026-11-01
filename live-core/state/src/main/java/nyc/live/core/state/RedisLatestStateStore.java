package nyc.live.core.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

/**
 * Redis-backed {@link LatestStateStore}.
 *
 * <p>Layout (prefix e.g. {@code tracknyc:trains}):
 * <ul>
 *   <li>{@code <prefix>:e:<id>} — JSON document, {@code SET ... PX ttl}</li>
 *   <li>{@code <prefix>:idx} — sorted set of ids scored by expiry time (ms), so a full read is one
 *       {@code ZRANGE} + one {@code MGET} and expired ids are trimmed with {@code ZREMRANGEBYSCORE}</li>
 *   <li>{@code <prefix>:doc:<key>} — auxiliary documents</li>
 * </ul>
 * Writes are pipelined: one round trip per tick no matter how many trains move.
 */
public class RedisLatestStateStore<T extends Identified> implements LatestStateStore<T> {

    private static final Logger log = LoggerFactory.getLogger(RedisLatestStateStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Class<T> type;
    private final String prefix;
    private final Clock clock;

    public RedisLatestStateStore(StringRedisTemplate redis, ObjectMapper mapper, Class<T> type, String prefix,
                                 Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.type = type;
        this.prefix = prefix;
        this.clock = clock;
    }

    String entityKey(String id) {
        return prefix + ":e:" + id;
    }

    String indexKey() {
        return prefix + ":idx";
    }

    String docKey(String key) {
        return prefix + ":doc:" + key;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void upsertAll(Collection<T> items, Duration ttl) {
        if (items.isEmpty()) {
            return;
        }
        double expiresAt = clock.millis() + ttl.toMillis();
        byte[] idx = utf8(indexKey());
        List<byte[][]> rows = new ArrayList<>(items.size());
        for (T item : items) {
            rows.add(new byte[][] {utf8(entityKey(item.id())), utf8(toJson(item)), utf8(item.id())});
        }
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (byte[][] row : rows) {
                connection.stringCommands().set(row[0], row[1], Expiration.milliseconds(ttl.toMillis()),
                        RedisStringCommands.SetOption.upsert());
                connection.zSetCommands().zAdd(idx, expiresAt, row[2]);
            }
            return null;
        });
    }

    @Override
    public void removeAll(Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        redis.delete(ids.stream().map(this::entityKey).toList());
        redis.opsForZSet().remove(indexKey(), ids.toArray());
    }

    @Override
    public Map<String, T> loadAll() {
        redis.opsForZSet().removeRangeByScore(indexKey(), Double.NEGATIVE_INFINITY, clock.millis());
        Set<String> ids = redis.opsForZSet().range(indexKey(), 0, -1);
        Map<String, T> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        List<String> idList = new ArrayList<>(ids);
        List<String> values = redis.opsForValue().multiGet(idList.stream().map(this::entityKey).toList());
        if (values == null) {
            return out;
        }
        for (int i = 0; i < idList.size(); i++) {
            String json = values.get(i);
            if (json != null) {
                T value = fromJson(json);
                if (value != null) {
                    out.put(idList.get(i), value);
                }
            }
        }
        return out;
    }

    @Override
    public Optional<T> get(String id) {
        return Optional.ofNullable(redis.opsForValue().get(entityKey(id))).map(this::fromJson);
    }

    @Override
    public void putDocument(String key, String json, Duration ttl) {
        redis.opsForValue().set(docKey(key), json, ttl);
    }

    @Override
    public Optional<String> getDocument(String key) {
        return Optional.ofNullable(redis.opsForValue().get(docKey(key)));
    }

    private String toJson(T item) {
        try {
            return mapper.writeValueAsString(item);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + item.id(), e);
        }
    }

    private T fromJson(String json) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            // A document written by an older version: skip it rather than failing the whole read.
            log.warn("skipping unreadable {} document: {}", type.getSimpleName(), e.getOriginalMessage());
            return null;
        }
    }
}
