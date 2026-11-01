package nyc.live.core.state;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Redis Pub/Sub implementation of {@link MessageBus}. */
public class RedisMessageBus implements MessageBus {

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;

    public RedisMessageBus(StringRedisTemplate redis, RedisMessageListenerContainer container) {
        this.redis = redis;
        this.container = container;
    }

    @Override
    public void publish(String channel, String payload) {
        redis.convertAndSend(channel, payload);
    }

    @Override
    public AutoCloseable subscribe(String channel, Consumer<String> handler) {
        ChannelTopic topic = new ChannelTopic(channel);
        MessageListener listener = (message, pattern) ->
                handler.accept(new String(message.getBody(), StandardCharsets.UTF_8));
        container.addMessageListener(listener, topic);
        return () -> container.removeMessageListener(listener, topic);
    }
}
