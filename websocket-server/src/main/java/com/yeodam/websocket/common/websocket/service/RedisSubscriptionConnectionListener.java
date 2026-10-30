package com.yeodam.websocket.common.websocket.service;

import lombok.extern.slf4j.Slf4j;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;

@Slf4j
public class RedisSubscriptionConnectionListener
        implements RedisConnectionStateListener, AutoCloseable {

    private final AbstractRedisClient redisClient;
    private final WebSocketSessionManager sessionManager;

    public RedisSubscriptionConnectionListener(
            LettuceConnectionFactory connectionFactory,
            WebSocketSessionManager sessionManager
    ) {
        this.redisClient = connectionFactory.getRequiredNativeClient();
        this.sessionManager = sessionManager;
        this.redisClient.addListener(this);
    }

    @Override
    public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
        if (connection instanceof StatefulRedisPubSubConnection<?, ?>) {
            log.atWarn().addKeyValue("event", "redis_subscription_connection")
                    .addKeyValue("result", "disconnected")
                    .log("WebSocket Redis Pub/Sub connection disconnected.");
            sessionManager.closeConnectionsForRedisFailure();
        }
    }

    @Override
    public void close() {
        redisClient.removeListener(this);
    }
}
