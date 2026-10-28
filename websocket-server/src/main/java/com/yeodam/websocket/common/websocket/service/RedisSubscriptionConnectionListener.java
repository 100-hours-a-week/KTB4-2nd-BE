package com.yeodam.websocket.common.websocket.service;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

public class RedisSubscriptionConnectionListener
        implements RedisConnectionStateListener, AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(RedisSubscriptionConnectionListener.class);

    private final AbstractRedisClient redisClient;
    private final RedisSubscriptionState subscriptionState;

    public RedisSubscriptionConnectionListener(
            LettuceConnectionFactory connectionFactory,
            RedisSubscriptionState subscriptionState
    ) {
        this.redisClient = connectionFactory.getRequiredNativeClient();
        this.subscriptionState = subscriptionState;
        this.redisClient.addListener(this);
    }

    @Override
    public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
        if (connection instanceof StatefulRedisPubSubConnection<?, ?>
                && subscriptionState.markUnavailable()) {
            log.warn("WebSocket Redis Pub/Sub connection disconnected.");
        }
    }

    @Override
    public void close() {
        redisClient.removeListener(this);
    }
}