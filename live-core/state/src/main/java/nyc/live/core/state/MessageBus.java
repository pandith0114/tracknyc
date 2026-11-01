package nyc.live.core.state;

import java.util.function.Consumer;

/**
 * Minimal publish/subscribe abstraction. The Redis implementation lets one ingest process
 * fan out to any number of push-API instances behind a load balancer.
 */
public interface MessageBus {

    void publish(String channel, String payload);

    /** Registers a handler; closing the returned handle unsubscribes. */
    AutoCloseable subscribe(String channel, Consumer<String> handler);
}
