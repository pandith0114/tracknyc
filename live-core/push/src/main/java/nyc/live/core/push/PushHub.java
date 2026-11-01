package nyc.live.core.push;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import nyc.live.core.state.Identified;

/**
 * Transport-independent core of the push gateway.
 *
 * <p>Holds the current state, coalesces incoming diffs, and on every {@link #flush()} sends
 * one diff to every connected client. New clients get a snapshot first. All mutation happens
 * under the hub's monitor, which guarantees that a client sees exactly the snapshot at seq
 * {@code S} followed by diffs {@code S+1, S+2, ...} — never a gap or a duplicate.
 */
public class PushHub<T extends Identified> {

    /** A connected viewer. Implementations wrap an SSE emitter, a WebSocket session, a test list... */
    public interface Client {
        void send(String event, long id, String json) throws IOException;

        void comment(String text) throws IOException;

        void close();
    }

    /** Hooks for metrics; all no-ops by default. */
    public interface Listener {
        default void diffSent(Duration lag, int bytes, int clients) {
        }

        default void clientsChanged(int clients) {
        }

        default void clientDropped(String reason) {
        }
    }

    private final Function<Object, String> json;
    private final Clock clock;
    private final int maxClients;
    private final Listener listener;
    private final Map<String, T> current = new LinkedHashMap<>();
    private final DiffAccumulator<T> pending = new DiffAccumulator<>();
    private final List<Client> clients = new ArrayList<>();
    private final Map<String, String> lastEvents = new LinkedHashMap<>();
    private long seq;
    private Instant stateGeneratedAt;

    public PushHub(Function<Object, String> json, Clock clock, int maxClients, Listener listener) {
        this.json = json;
        this.clock = clock;
        this.maxClients = maxClients;
        this.listener = listener == null ? new Listener() { } : listener;
        this.stateGeneratedAt = clock.instant();
    }

    /** Queues a producer diff; it is sent on the next {@link #flush()}. */
    public synchronized void offer(Diff<T> diff) {
        pending.add(diff);
    }

    /** Queues whatever changes turn the current state into {@code full} (e.g. a warm start from Redis). */
    public synchronized void replaceAll(Map<String, T> full, Instant generatedAt) {
        pending.drain(0); // discard: superseded by the full state
        pending.add(DiffEngine.compute(0, generatedAt, current, full));
    }

    /** Sends coalesced changes to every client. Returns the diff that was sent, if any. */
    public synchronized Optional<Diff<T>> flush() {
        Optional<Diff<T>> drained = pending.drain(seq + 1);
        if (drained.isEmpty()) {
            return Optional.empty();
        }
        Diff<T> diff = drained.get();
        DiffEngine.apply(current, diff);
        seq = diff.seq();
        stateGeneratedAt = diff.generatedAt();
        Instant now = clock.instant();
        String payload = json.apply(PushMessages.DiffMessage.of(diff, now));
        sendToAll("diff", seq, payload);
        listener.diffSent(Duration.between(diff.generatedAt(), now), payload.length(), clients.size());
        return Optional.of(diff);
    }

    /**
     * Registers a client and immediately sends it the snapshot plus the latest auxiliary events.
     * Returns false (and sends nothing) when the hub is full.
     */
    public synchronized boolean connect(Client client) {
        if (clients.size() >= maxClients) {
            listener.clientDropped("capacity");
            return false;
        }
        try {
            String snapshot = json.apply(PushMessages.Snapshot.of(seq, stateGeneratedAt, clock.instant(),
                    new ArrayList<>(current.values())));
            client.send("snapshot", seq, snapshot);
            for (Map.Entry<String, String> e : lastEvents.entrySet()) {
                client.send(e.getKey(), seq, e.getValue());
            }
        } catch (IOException e) {
            client.close();
            listener.clientDropped("io");
            return false;
        }
        clients.add(client);
        listener.clientsChanged(clients.size());
        return true;
    }

    public synchronized void disconnect(Client client) {
        if (clients.remove(client)) {
            listener.clientsChanged(clients.size());
        }
    }

    /** Sends a named auxiliary event (e.g. feed health) now, and to every future client on connect. */
    public synchronized void broadcast(String event, Object payload) {
        String body = json.apply(payload);
        lastEvents.put(event, body);
        sendToAll(event, seq, body);
    }

    /** Keeps idle connections open through proxies and detects dead clients. */
    public synchronized void heartbeat() {
        List<Client> dead = new ArrayList<>();
        for (Client c : clients) {
            try {
                c.comment("hb " + clock.instant().getEpochSecond());
            } catch (IOException | RuntimeException e) {
                dead.add(c);
            }
        }
        drop(dead, "heartbeat");
    }

    private void sendToAll(String event, long id, String payload) {
        List<Client> dead = new ArrayList<>();
        for (Client c : clients) {
            try {
                c.send(event, id, payload);
            } catch (IOException | RuntimeException e) {
                dead.add(c);
            }
        }
        drop(dead, "io");
    }

    private void drop(List<Client> dead, String reason) {
        if (dead.isEmpty()) {
            return;
        }
        for (Client c : dead) {
            clients.remove(c);
            c.close();
            listener.clientDropped(reason);
        }
        listener.clientsChanged(clients.size());
    }

    public synchronized int clientCount() {
        return clients.size();
    }

    public synchronized long seq() {
        return seq;
    }

    public synchronized Map<String, T> currentState() {
        return new LinkedHashMap<>(current);
    }
}
