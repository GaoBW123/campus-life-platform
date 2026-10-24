package com.campus.life.config;

import com.campus.life.listener.CaffeineSyncListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisPubSubConfig {

    public static final String SYNC_TOPIC = "cache:sync:topic";

    @Bean
    public RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
                                                   CaffeineSyncListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 订阅我们自定义的频道
        container.addMessageListener(listener, new PatternTopic(SYNC_TOPIC));
        return container;
    }
}