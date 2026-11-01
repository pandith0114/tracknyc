package nyc.live.core.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nyc.live.core.state.Identified;
import org.junit.jupiter.api.Test;

class PushHubTest {

    record Train(String id, double lat) implements Identified {
    }

    static final class RecordingClient implements PushHub.Client {
        final List<String> events = new ArrayList<>();
        boolean broken;
        boolean closed;

        @Override
        public void send(String event, long id, String json) throws IOException {
            if (broken) {
                throw new IOException("client went away");
            }
            events.add(event + "#" + id);
        }

        @Override
        public void comment(String text) throws IOException {
            if (broken) {
                throw new IOException("client went away");
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final Instant t0 = Instant.parse("2026-10-01T12:00:00Z");
    private final List<String> payloads = new ArrayList<>();
    private final PushHub<Train> hub = new PushHub<>(o -> {
        String s = o.toString();
        payloads.add(s);
        return s;
    }, Clock.fixed(t0, ZoneOffset.UTC), 2, null);

    private static Map<String, Train> state(Train... trains) {
        Map<String, Train> m = new LinkedHashMap<>();
        for (Train t : trains) {
            m.put(t.id(), t);
        }
        return m;
    }

    @Test
    void diffEngineFindsUpsertsAndRemoves() {
        Diff<Train> d = DiffEngine.compute(7, t0, state(new Train("a", 1), new Train("b", 2)),
                state(new Train("a", 1), new Train("b", 3), new Train("c", 4)));
        assertEquals(7, d.seq());
        assertEquals(List.of(new Train("b", 3), new Train("c", 4)), d.upserts());
        assertTrue(d.removes().isEmpty());

        Diff<Train> gone = DiffEngine.compute(8, t0, state(new Train("a", 1)), state());
        assertEquals(List.of("a"), gone.removes());

        Map<String, Train> s = state(new Train("a", 1), new Train("b", 2));
        DiffEngine.apply(s, d);
        assertEquals(state(new Train("a", 1), new Train("b", 3), new Train("c", 4)), s);
    }

    @Test
    void accumulatorCoalescesAndLaterChangesWin() {
        DiffAccumulator<Train> acc = new DiffAccumulator<>();
        acc.add(new Diff<>(1, t0, List.of(new Train("a", 1)), List.of()));
        acc.add(new Diff<>(2, t0.plusSeconds(5), List.of(new Train("a", 2)), List.of("b")));
        acc.add(new Diff<>(3, t0.plusSeconds(10), List.of(new Train("b", 9)), List.of("a")));
        Diff<Train> merged = acc.drain(42).orElseThrow();
        assertEquals(42, merged.seq());
        assertEquals(t0, merged.generatedAt(), "oldest generatedAt is kept for lag measurement");
        assertEquals(List.of(new Train("b", 9)), merged.upserts());
        assertEquals(List.of("a"), merged.removes());
        assertTrue(acc.isEmpty());
        assertTrue(acc.drain(43).isEmpty());
    }

    @Test
    void newClientGetsSnapshotThenOnlyLaterDiffs() {
        hub.offer(new Diff<>(1, t0, List.of(new Train("a", 1)), List.of()));
        hub.flush();
        RecordingClient c = new RecordingClient();
        assertTrue(hub.connect(c));
        assertEquals(List.of("snapshot#1"), c.events);
        assertTrue(payloads.get(payloads.size() - 1).contains("Train[id=a"), "snapshot carries current state");

        assertTrue(hub.flush().isEmpty(), "nothing pending, nothing sent");
        hub.offer(new Diff<>(2, t0, List.of(new Train("a", 2)), List.of()));
        hub.flush();
        assertEquals(List.of("snapshot#1", "diff#2"), c.events);
    }

    @Test
    void auxiliaryEventsAreReplayedToNewClients() {
        hub.broadcast("health", "all good");
        RecordingClient c = new RecordingClient();
        hub.connect(c);
        assertEquals(List.of("snapshot#0", "health#0"), c.events);
    }

    @Test
    void capacityIsEnforcedAndBrokenClientsAreDropped() {
        RecordingClient a = new RecordingClient();
        RecordingClient b = new RecordingClient();
        assertTrue(hub.connect(a));
        assertTrue(hub.connect(b));
        assertFalse(hub.connect(new RecordingClient()), "max 2 clients");
        b.broken = true;
        hub.offer(new Diff<>(1, t0, List.of(new Train("x", 1)), List.of()));
        hub.flush();
        assertEquals(1, hub.clientCount());
        assertTrue(b.closed);
        a.broken = true;
        hub.heartbeat();
        assertEquals(0, hub.clientCount());
    }

    @Test
    void replaceAllSendsOnlyTheDifference() {
        hub.offer(new Diff<>(1, t0, List.of(new Train("a", 1), new Train("b", 2)), List.of()));
        hub.flush();
        hub.replaceAll(state(new Train("a", 1), new Train("c", 3)), t0.plusSeconds(5));
        Diff<Train> sent = hub.flush().orElseThrow();
        assertEquals(List.of(new Train("c", 3)), sent.upserts());
        assertEquals(List.of("b"), sent.removes());
        assertEquals(2, hub.seq());
        assertEquals(2, hub.currentState().size());
    }
}
