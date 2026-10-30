package com.yeodam.websocket.common.websocket.metrics;

import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebSocketMetricsTest {

    @Test
    void 초기값이_아닌_현재_연결과_구독_수를_조회한다() {
        WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
        SimpUserRegistry users = mock(SimpUserRegistry.class);
        when(sessions.getSessions()).thenReturn(List.of());
        when(users.getUsers()).thenReturn(Set.of());

        var registry = new SimpleMeterRegistry();
        try {
            new WebSocketMetrics(sessions, users).bindTo(registry);
            assertThat(registry.get("websocket.connections").gauge().value()).isZero();
            assertThat(registry.get("websocket.subscriptions").gauge().value()).isZero();

            when(sessions.getSessions()).thenReturn(List.of(
                    mock(WebSocketSession.class), mock(WebSocketSession.class)));
            SimpUser user = mock(SimpUser.class);
            SimpSession first = mock(SimpSession.class);
            SimpSession second = mock(SimpSession.class);
            when(first.getSubscriptions()).thenReturn(Set.of(
                    mock(SimpSubscription.class), mock(SimpSubscription.class)));
            when(second.getSubscriptions()).thenReturn(Set.of(mock(SimpSubscription.class)));
            when(user.getSessions()).thenReturn(Set.of(first, second));
            when(users.getUsers()).thenReturn(Set.of(user));

            assertThat(registry.get("websocket.connections").gauge().value()).isEqualTo(2);
            assertThat(registry.get("websocket.subscriptions").gauge().value()).isEqualTo(3);

            when(sessions.getSessions()).thenReturn(List.of());
            when(users.getUsers()).thenReturn(Set.of());
            assertThat(registry.get("websocket.connections").gauge().value()).isZero();
            assertThat(registry.get("websocket.subscriptions").gauge().value()).isZero();
        } finally {
            registry.close();
        }
    }

}
