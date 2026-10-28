package com.yeodam.websocket.common.websocket.session;

import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;
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
import java.util.HashMap;
import java.util.Map;
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
    private final RedisSubscriptionState subscriptionState = new RedisSubscriptionState();
    private final WebSocketSessionManager manager = new WebSocketSessionManager(scheduler, subscriptionState);
    private final ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

    @BeforeEach
    void prepareConnection() {
        subscriptionState.markSubscribed();
        when(session.getId()).thenReturn("connection-a");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketSessionCredentials.ATTRIBUTE_NAME,
                new WebSocketSessionCredentials("test-access", "test-context"));
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new WebSocketPrincipal(42L, "login-session", EXPIRES_AT), null, List.of()
        ));
        doReturn(reservation).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void schedulesAtTheConnectionsAccessTokenExpiry() {
        assertThat(manager.register(session)).isTrue();

        verify(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        assertThat(manager.getSessions()).containsExactly(session);
    }

    @Test
    void refusesRegistrationWithoutSchedulingExpiryWhenSubscriptionIsUnavailable() {
        subscriptionState.markUnavailable();

        assertThat(manager.register(session)).isFalse();

        assertThat(manager.getSessions()).isEmpty();
        verifyNoInteractions(scheduler);
    }

    @Test
    void allowsRegistrationAfterSubscriptionRecovers() {
        subscriptionState.markUnavailable();
        assertThat(manager.register(session)).isFalse();
        subscriptionState.markSubscribed();

        assertThat(manager.register(session)).isTrue();

        assertThat(manager.getSessions()).containsExactly(session);
        verify(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
    }

    @Test
    void cancelsTheReservationWhenConnectionClosesFirst() {
        manager.register(session);

        manager.unregister("connection-a");

        verify(reservation).cancel(false);
        assertThat(session.getAttributes()).doesNotContainKey(WebSocketSessionCredentials.ATTRIBUTE_NAME);
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

    @Test
    void groupsConnectionsByLoginSessionRatherThanConnectionId() {
        manager.register(session);
        WebSocketSession another = mock(WebSocketSession.class);
        when(another.getId()).thenReturn("connection-b");
        var principal = session.getPrincipal();
        when(another.getPrincipal()).thenReturn(principal);
        manager.register(another);

        assertThat(manager.getSessionsByLoginSession()).hasSize(1);
        assertThat(manager.getSessionsByLoginSession().get("login-session"))
                .containsExactlyInAnyOrder(session, another);
    }

    @Test
    void closesOnlyConnectionsCapturedBeforeTheScheduledTaskRuns() throws Exception {
        manager.register(session);
        when(session.isOpen()).thenReturn(true);
        clearInvocations(scheduler);

        manager.closeConnectionsForRedisFailure();

        verify(scheduler).schedule(task.capture(), any(Instant.class));
        verify(session, never()).close(any(CloseStatus.class));
        WebSocketSession later = mock(WebSocketSession.class);
        when(later.getId()).thenReturn("later-connection");
        var principal = session.getPrincipal();
        when(later.getPrincipal()).thenReturn(principal);
        subscriptionState.markSubscribed();
        assertThat(manager.register(later)).isTrue();

        task.getValue().run();

        verify(session).close(CloseStatus.SERVER_ERROR);
        verify(later, never()).close(any(CloseStatus.class));
    }

    @Test
    void skipsConnectionsThatAreAlreadyClosedWhenTheTaskRuns() throws Exception {
        manager.register(session);
        when(session.isOpen()).thenReturn(false);
        clearInvocations(scheduler);

        manager.closeConnectionsForRedisFailure();
        verify(scheduler).schedule(task.capture(), any(Instant.class));
        task.getValue().run();

        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void continuesClosingOtherConnectionsWhenOneFails() throws Exception {
        manager.register(session);
        when(session.isOpen()).thenReturn(true);
        WebSocketSession another = mock(WebSocketSession.class);
        when(another.getId()).thenReturn("connection-b");
        var principal = session.getPrincipal();
        when(another.getPrincipal()).thenReturn(principal);
        when(another.isOpen()).thenReturn(true);
        manager.register(another);
        doThrow(new IOException("test close failure"))
                .when(session).close(CloseStatus.SERVER_ERROR);
        doThrow(new IllegalStateException("test close failure"))
                .when(another).close(CloseStatus.SERVER_ERROR);
        clearInvocations(scheduler);

        manager.closeConnectionsForRedisFailure();
        verify(scheduler).schedule(task.capture(), any(Instant.class));
        task.getValue().run();

        verify(session).close(CloseStatus.SERVER_ERROR);
        verify(another).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void removesCredentialsAndExpiryReservationThroughTheCloseCallback() throws Exception {
        manager.register(session);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            manager.unregister("connection-a");
            return null;
        }).when(session).close(CloseStatus.SERVER_ERROR);
        clearInvocations(scheduler);

        manager.closeConnectionsForRedisFailure();
        verify(scheduler).schedule(task.capture(), any(Instant.class));
        task.getValue().run();

        assertThat(manager.getSessions()).isEmpty();
        assertThat(session.getAttributes()).doesNotContainKey(WebSocketSessionCredentials.ATTRIBUTE_NAME);
        verify(reservation).cancel(false);
    }
}
