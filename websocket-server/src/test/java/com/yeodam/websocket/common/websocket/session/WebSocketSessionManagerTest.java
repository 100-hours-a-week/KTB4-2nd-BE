package com.yeodam.websocket.common.websocket.session;

import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebSocketSessionManagerTest {

    private static final Instant EXPIRES_AT = Instant.parse("2099-01-01T00:00:00Z");
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> reservation = mock(ScheduledFuture.class);
    private final WebSocketSession session = mock(WebSocketSession.class);
    private final WebSocketSessionManager manager = new WebSocketSessionManager(scheduler);
    private final ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

    @BeforeEach
    void prepareConnection() {
        when(session.getId()).thenReturn("connection-a");
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new WebSocketPrincipal(42L, "login-session", EXPIRES_AT), null, List.of()
        ));
        doReturn(reservation).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void schedulesAtTheConnectionsAccessTokenExpiry() {
        manager.register(session);

        verify(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        assertThat(manager.getSessions()).containsExactly(session);
    }

    @Test
    void cancelsTheReservationWhenConnectionClosesFirst() {
        manager.register(session);

        manager.unregister("connection-a");

        verify(reservation).cancel(false);
        assertThat(manager.getSessions()).isEmpty();
    }

    @Test
    void closesExpiredConnectionAndRemovesItThroughTheCloseCallback() throws Exception {
        manager.register(session);
        verify(scheduler).schedule(task.capture(), eq(EXPIRES_AT));
        doAnswer(invocation -> {
            manager.unregister("connection-a");
            return null;
        }).when(session).close(CloseStatus.POLICY_VIOLATION);

        task.getValue().run();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(reservation, never()).cancel(anyBoolean());
        assertThat(manager.getSessions()).isEmpty();
    }

    @Test
    void skipsAnAlreadyRemovedConnectionEvenIfItsTaskStarts() throws Exception {
        manager.register(session);
        verify(scheduler).schedule(task.capture(), eq(EXPIRES_AT));
        manager.unregister("connection-a");

        task.getValue().run();

        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void removesConnectionAndPropagatesSchedulingFailure() {
        RejectedExecutionException failure = new RejectedExecutionException("test rejection");
        doThrow(failure).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        assertThatThrownBy(() -> manager.register(session)).isSameAs(failure);
        assertThat(manager.getSessions()).isEmpty();
    }

    @Test
    void retainsConnectionIfClosingFails() throws Exception {
        manager.register(session);
        verify(scheduler).schedule(task.capture(), eq(EXPIRES_AT));
        doThrow(new IOException("test close failure")).when(session).close(CloseStatus.POLICY_VIOLATION);

        task.getValue().run();

        assertThat(manager.getSessions()).containsExactly(session);
    }
}
