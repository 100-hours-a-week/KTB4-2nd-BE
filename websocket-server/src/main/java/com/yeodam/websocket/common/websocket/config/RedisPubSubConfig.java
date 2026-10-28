package com.yeodam.websocket.common.websocket.config;

import com.yeodam.websocket.common.websocket.service.WebSocketEventSubscriber;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionConnectionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebSocketEventProperties.class)
public class RedisPubSubConfig {

    @Bean(destroyMethod = "close")
    RedisSubscriptionConnectionListener redisSubscriptionConnectionListener(
            LettuceConnectionFactory connectionFactory,
            WebSocketSessionManager sessionManager
    ) {
        return new RedisSubscriptionConnectionListener(
                connectionFactory,
                sessionManager
        );
    }

    @Bean
    RedisMessageListenerContainer webSocketEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            WebSocketEventProperties properties,
            WebSocketEventSubscriber subscriber
    ) {
        RedisMessageListenerContainer container =
                new RedisMessageListenerContainer();

        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                subscriber,
                new ChannelTopic(properties.channel())
        );

        return container;
    }
}
