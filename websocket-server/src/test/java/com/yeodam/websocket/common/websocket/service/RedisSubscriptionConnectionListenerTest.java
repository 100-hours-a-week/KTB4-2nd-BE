package com.yeodam.websocket.common.websocket.service;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RedisSubscriptionConnectionListenerTest {

    private final AbstractRedisClient client = mock(AbstractRedisClient.class);
    private final RedisSubscriptionState state = new RedisSubscriptionState();
    private final RedisSubscriptionConnectionListener listener = createListener();

    private RedisSubscriptionConnectionListener createListener() {
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        when(factory.getRequiredNativeClient()).thenReturn(client);
        return new RedisSubscriptionConnectionListener(factory, state);
    }

    @Test
    void registersWithTheNativeClientAndRemovesItselfOnClose() {
        verify(client).addListener(listener);
        listener.close();
        verify(client).removeListener(listener);
    }

    @Test
    void marksSubscriptionUnavailableWhenPubSubConnectionDisconnects() {
        RedisChannelHandler<?, ?> connection = mock(
                RedisChannelHandler.class,
                withSettings().extraInterfaces(StatefulRedisPubSubConnection.class)
        );
        state.markSubscribed();

        listener.onRedisDisconnected(connection);

        assertThat(state.isReady()).isFalse();
        listener.onRedisDisconnected(connection);
        assertThat(state.isReady()).isFalse();
    }

    @Test
    void keepsSubscriptionReadyWhenAnOrdinaryConnectionDisconnects() {
        state.markSubscribed();

        listener.onRedisDisconnected(mock(RedisChannelHandler.class));

        assertThat(state.isReady()).isTrue();
    }

    @Test
    void doesNotMarkReadyOnlyBecauseTcpConnectionWasRestored() {
        RedisChannelHandler<?, ?> connection = mock(
                RedisChannelHandler.class,
                withSettings().extraInterfaces(StatefulRedisPubSubConnection.class)
        );
        state.markSubscribed();
        listener.onRedisDisconnected(connection);

        listener.onRedisConnected(connection, null);

        assertThat(state.isReady()).isFalse();
    }
}
