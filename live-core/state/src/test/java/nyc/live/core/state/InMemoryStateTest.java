package nyc.live.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryStateTest {

    @Test
    void entriesExpireAfterTtl() {
        Instant t0 = Instant.parse("2026-10-01T12:00:00Z");
        InMemoryLatestStateStore<Thing> early = new InMemoryLatestStateStore<>(Clock.fixed(t0, ZoneOffset.UTC));
        early.upsertAll(List.of(new Thing("a", 40.7, -74.0), new Thing("b", 40.8, -73.9)), Duration.ofSeconds(30));
        assertEquals(2, early.loadAll().size());
        assertEquals(40.7, early.get("a").orElseThrow().lat(), 0);
        early.removeAll(List.of("b"));
        assertEquals(1, early.loadAll().size());
        early.putDocument("health", "{}", Duration.ofSeconds(5));
        assertEquals("{}", early.getDocument("health").orElseThrow());
    }

    @Test
    void expiredEntriesAreNotReturned() {
        MutableTime time = new MutableTime(Instant.parse("2026-10-01T12:00:00Z"));
        InMemoryLatestStateStore<Thing> store = new InMemoryLatestStateStore<>(time);
        store.upsertAll(List.of(new Thing("a", 40.7, -74.0)), Duration.ofSeconds(30));
        store.putDocument("health", "{}", Duration.ofSeconds(5));
        time.now = time.now.plusSeconds(31);
        assertTrue(store.loadAll().isEmpty());
        assertTrue(store.get("a").isEmpty());
        assertTrue(store.getDocument("health").isEmpty());
    }

    @Test
    void busDeliversToSubscribersUntilClosed() throws Exception {
        InMemoryMessageBus bus = new InMemoryMessageBus();
        List<String> got = new ArrayList<>();
        AutoCloseable sub = bus.subscribe("positions", got::add);
        bus.publish("positions", "one");
        bus.publish("other", "ignored");
        sub.close();
        bus.publish("positions", "two");
        assertEquals(List.of("one"), got);
    }

    static final class MutableTime extends Clock {
        Instant now;

        MutableTime(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
