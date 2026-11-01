package nyc.tracknyc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import nyc.live.core.state.InMemoryLatestStateStore;
import nyc.live.core.state.InMemoryMessageBus;
import nyc.live.core.state.LatestStateStore;
import nyc.live.core.state.MessageBus;
import nyc.live.core.state.RedisLatestStateStore;
import nyc.live.core.state.RedisMessageBus;
import nyc.tracknyc.position.TrainState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Latest-state store and message bus: Redis by default, in-memory when
 * {@code tracknyc.state.store=memory} (tests, single-process dev, the {@code nodb} profile).
 */
@Configuration(proxyBeanMethods = false)
public class StateConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "tracknyc.state", name = "store", havingValue = "redis", matchIfMissing = true)
    static class RedisState {

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }

        @Bean(destroyMethod = "stop")
        RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
            RedisMessageListenerContainer c = new RedisMessageListenerContainer();
            c.setConnectionFactory(factory);
            c.afterPropertiesSet();
            c.start();
            return c;
        }

        @Bean
        LatestStateStore<TrainState> trainStateStore(StringRedisTemplate redis, ObjectMapper mapper,
                                                     TrackNycProperties props, Clock clock) {
            return new RedisLatestStateStore<>(redis, mapper, TrainState.class,
                    props.getState().getKeyPrefix() + ":trains", clock);
        }

        @Bean
        MessageBus messageBus(StringRedisTemplate redis, RedisMessageListenerContainer container) {
            return new RedisMessageBus(redis, container);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "tracknyc.state", name = "store", havingValue = "memory")
    static class InMemoryState {

        @Bean
        LatestStateStore<TrainState> trainStateStore(Clock clock) {
            return new InMemoryLatestStateStore<>(clock);
        }

        @Bean
        MessageBus messageBus() {
            return new InMemoryMessageBus();
        }
    }
}
