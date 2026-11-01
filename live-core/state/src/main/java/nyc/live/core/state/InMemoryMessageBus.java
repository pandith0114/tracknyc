package nyc.live.core.state;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Synchronous in-process bus, used when Redis is not configured (tests, single node). */
public class InMemoryMessageBus implements MessageBus {

    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();

    @Override
    public void publish(String channel, String payload) {
        for (Consumer<String> h : handlers.getOrDefault(channel, List.of())) {
            h.accept(payload);
        }
    }

    @Override
    public AutoCloseable subscribe(String channel, Consumer<String> handler) {
        handlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(handler);
        return () -> handlers.getOrDefault(channel, List.of()).remove(handler);
    }
}
