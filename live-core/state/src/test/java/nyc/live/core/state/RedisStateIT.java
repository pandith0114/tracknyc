package nyc.live.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Testcontainers integration test against a real Redis 7. Run with {@code ./gradlew integrationTest}. */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class RedisStateIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static RedisMessageListenerContainer listeners;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        listeners = new RedisMessageListenerContainer();
        listeners.setConnectionFactory(factory);
        listeners.afterPropertiesSet();
        listeners.start();
    }

    @AfterAll
    static void close() {
        listeners.stop();
        factory.destroy();
    }

    @Test
    void upsertLoadAndExpire() throws Exception {
        RedisLatestStateStore<Thing> store = new RedisLatestStateStore<>(redis, new ObjectMapper(), Thing.class,
                "it:things", Clock.systemUTC());
        store.upsertAll(List.of(new Thing("a", 40.7, -74.0), new Thing("b", 40.8, -73.9)), Duration.ofSeconds(30));
        assertEquals(2, store.loadAll().size());
        assertEquals(-73.9, store.get("b").orElseThrow().lon(), 0);

        Long ttl = redis.getExpire("it:things:e:a", TimeUnit.SECONDS);
        assertTrue(ttl != null && ttl > 0 && ttl <= 30, "TTL set on entity key");

        store.removeAll(List.of("a"));
        assertEquals(List.of("b"), List.copyOf(store.loadAll().keySet()));

        store.upsertAll(List.of(new Thing("short", 40.0, -74.0)), Duration.ofMillis(200));
        Thread.sleep(400);
        assertTrue(store.get("short").isEmpty(), "expired by Redis");
        assertTrue(!store.loadAll().containsKey("short"), "trimmed from the index");

        store.putDocument("health", "{\"ok\":true}", Duration.ofSeconds(10));
        assertEquals("{\"ok\":true}", store.getDocument("health").orElseThrow());
    }

    @Test
    void pubSubRoundTrip() throws Exception {
        RedisMessageBus bus = new RedisMessageBus(redis, listeners);
        CompletableFuture<String> received = new CompletableFuture<>();
        try (AutoCloseable ignored = bus.subscribe("it:positions", received::complete)) {
            // Subscription registration is asynchronous; publish until it lands.
            for (int i = 0; i < 50 && !received.isDone(); i++) {
                bus.publish("it:positions", "{\"seq\":1}");
                Thread.sleep(100);
            }
            assertEquals("{\"seq\":1}", received.get(5, TimeUnit.SECONDS));
        }
    }
}
