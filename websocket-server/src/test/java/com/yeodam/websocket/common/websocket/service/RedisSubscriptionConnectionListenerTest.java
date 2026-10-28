package com.yeodam.websocket.common.websocket.service;

import com.yeodam.websocket.common.websocket.config.WebSocketProperties;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import org.springframework.scheduling.TaskScheduler;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RedisSubscriptionConnectionListenerTest {

    private final AbstractRedisClient client = mock(AbstractRedisClient.class);
    private final RedisSubscriptionState state = new RedisSubscriptionState();
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final WebSocketSessionManager sessionManager = new WebSocketSessionManager(scheduler, state, new WebSocketProperties(java.util.List.of(), 1000));
    private final RedisSubscriptionConnectionListener listener = createListener();

    private RedisSubscriptionConnectionListener createListener() {
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        when(factory.getRequiredNativeClient()).thenReturn(client);
        return new RedisSubscriptionConnectionListener(factory, sessionManager);
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
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
        listener.onRedisDisconnected(connection);
        assertThat(state.isReady()).isFalse();
        verifyNoMoreInteractions(scheduler);
    }

    @Test
    void keepsSubscriptionReadyWhenAnOrdinaryConnectionDisconnects() {
        state.markSubscribed();

        listener.onRedisDisconnected(mock(RedisChannelHandler.class));

        assertThat(state.isReady()).isTrue();
        verifyNoInteractions(scheduler);
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
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
        verifyNoMoreInteractions(scheduler);
    }

    @Test
    void doesNotRequestClosureBeforeSubscriptionWasReady() {
        RedisChannelHandler<?, ?> connection = mock(
                RedisChannelHandler.class,
                withSettings().extraInterfaces(StatefulRedisPubSubConnection.class)
        );

        listener.onRedisDisconnected(connection);

        assertThat(state.isReady()).isFalse();
        verifyNoInteractions(scheduler);
    }

    @Test
    void requestsClosureAgainAfterARecoveredSubscriptionDisconnects() {
        RedisChannelHandler<?, ?> connection = mock(
                RedisChannelHandler.class,
                withSettings().extraInterfaces(StatefulRedisPubSubConnection.class)
        );
        doAnswer(invocation -> {
            assertThat(state.isReady()).isFalse();
            return null;
        }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        state.markSubscribed();
        listener.onRedisDisconnected(connection);
        state.markSubscribed();
        listener.onRedisDisconnected(connection);

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }
}
