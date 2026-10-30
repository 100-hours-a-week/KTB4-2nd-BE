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
    void 네이티브_클라이언트에_리스너를_등록하고_종료_시_제거한다() {
        verify(client).addListener(listener);
        listener.close();
        verify(client).removeListener(listener);
    }

    @Test
    void PubSub_연결이_끊기면_구독을_사용_불가로_표시한다() {
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
    void 일반_Redis_연결이_끊겨도_구독_준비_상태를_유지한다() {
        state.markSubscribed();

        listener.onRedisDisconnected(mock(RedisChannelHandler.class));

        assertThat(state.isReady()).isTrue();
        verifyNoInteractions(scheduler);
    }

    @Test
    void TCP_연결만_복구됐다고_구독_준비_완료로_표시하지_않는다() {
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
    void 구독_준비_전에는_연결_종료를_요청하지_않는다() {
        RedisChannelHandler<?, ?> connection = mock(
                RedisChannelHandler.class,
                withSettings().extraInterfaces(StatefulRedisPubSubConnection.class)
        );

        listener.onRedisDisconnected(connection);

        assertThat(state.isReady()).isFalse();
        verifyNoInteractions(scheduler);
    }

    @Test
    void 복구된_구독이_다시_끊기면_연결_종료를_다시_요청한다() {
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
