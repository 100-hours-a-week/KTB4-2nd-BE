package com.yeodam.websocket.common.websocket.session;

import com.yeodam.websocket.common.websocket.config.WebSocketProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import static org.mockito.ArgumentMatchers.any;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSessionTrackingDecoratorTest {

    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final RedisSubscriptionState subscriptionState = new RedisSubscriptionState();
    private final WebSocketSessionManager manager = new WebSocketSessionManager(scheduler, subscriptionState, new WebSocketProperties(java.util.List.of(), 1000));
    private final WebSocketHandler delegate = mock(WebSocketHandler.class);
    private final WebSocketSessionTrackingDecorator decorator =
            new WebSocketSessionTrackingDecorator(delegate, manager);

    @BeforeEach
    void prepareScheduler() {
        subscriptionState.markSubscribed();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
    }

    @Test
    void closesConnectionWithoutStartingStompWhenRegistrationIsRefused() throws Exception {
        WebSocketSession session = session("refused");
        subscriptionState.markUnavailable();

        decorator.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.SERVER_ERROR);
        verifyNoInteractions(delegate, scheduler);
        assertThat(manager.getSessions()).isEmpty();
    }

    @Test
    void registersBeforeDelegatingConnectionEstablished() throws Exception {
        WebSocketSession session = session("first");
        doAnswer(invocation -> {
            assertThat(manager.getSessions()).containsExactly(session);
            return null;
        }).when(delegate).afterConnectionEstablished(session);

        decorator.afterConnectionEstablished(session);

        verify(delegate).afterConnectionEstablished(session);
        assertThat(manager.getSessions()).containsExactly(session);
    }

    @Test
    void removesConnectionAndRethrowsEstablishmentFailure() throws Exception {
        WebSocketSession session = session("first");
        Exception failure = new Exception("test establishment failure");
        doThrow(failure).when(delegate).afterConnectionEstablished(session);

        assertThatThrownBy(() -> decorator.afterConnectionEstablished(session))
                .isSameAs(failure);
        assertThat(manager.getSessions()).isEmpty();
    }

    @Test
    void closesOneConnectionWithoutRemovingAnother() throws Exception {
        WebSocketSession first = session("first");
        WebSocketSession second = session("second");
        decorator.afterConnectionEstablished(first);
        decorator.afterConnectionEstablished(second);

        decorator.afterConnectionClosed(first, CloseStatus.NORMAL);

        verify(delegate).afterConnectionClosed(first, CloseStatus.NORMAL);
        assertThat(manager.getSessions()).containsExactly(second);
    }

    @Test
    void removesConnectionEvenWhenDelegateCloseFails() throws Exception {
        WebSocketSession session = session("first");
        manager.register(session);
        Exception failure = new Exception("test close failure");
        doThrow(failure).when(delegate).afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThatThrownBy(() -> decorator.afterConnectionClosed(session, CloseStatus.NORMAL))
                .isSameAs(failure);
        assertThat(manager.getSessions()).isEmpty();
    }

    @Test
    void returnsASnapshotUnaffectedByLaterRemoval() {
        WebSocketSession session = session("first");
        manager.register(session);
        var snapshot = manager.getSessions();

        manager.unregister("first");

        assertThat(snapshot).containsExactly(session);
        assertThat(manager.getSessions()).isEmpty();
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new WebSocketPrincipal(42L, "login-session", Instant.parse("2099-01-01T00:00:00Z")),
                null, List.of()
        ));
        return session;
    }
}
