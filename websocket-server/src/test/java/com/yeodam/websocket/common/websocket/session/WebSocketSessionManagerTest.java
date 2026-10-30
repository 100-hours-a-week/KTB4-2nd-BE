package com.yeodam.websocket.common.websocket.session;

import com.yeodam.websocket.common.websocket.config.WebSocketProperties;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

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
    private final WebSocketSessionManager manager = new WebSocketSessionManager(scheduler, subscriptionState, new WebSocketProperties(java.util.List.of(), 1000));
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
    void 종료_후에는_구독이_복구돼도_접속과_등록을_거절한다() {
        manager.shutdownConnections();
        subscriptionState.markSubscribed();

        assertThat(manager.canAcceptConnection()).isFalse();
        assertThat(manager.register(session)).isFalse();
        verifyNoInteractions(scheduler);
    }

    @Test
    void 재시작_코드로_연결을_닫고_종료_콜백에서_자원을_정리한다() throws Exception {
        manager.register(session);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            assertThat(manager.canAcceptConnection()).isFalse();
            assertThat(subscriptionState.isReady()).isFalse();
            manager.unregister("connection-a");
            return null;
        }).when(session).close(CloseStatus.SERVICE_RESTARTED);

        manager.shutdownConnections();
        manager.shutdownConnections();

        verify(session, times(1)).close(CloseStatus.SERVICE_RESTARTED);
        assertThat(manager.getSessions()).isEmpty();
        assertThat(session.getAttributes()).doesNotContainKey(WebSocketSessionCredentials.ATTRIBUTE_NAME);
        verify(reservation).cancel(false);
    }

    @Test
    void 서버_종료_후에는_Redis_장애_작업을_예약하지_않는다() {
        manager.shutdownConnections();
        subscriptionState.markSubscribed();

        manager.closeConnectionsForRedisFailure();

        verifyNoInteractions(scheduler);
    }

    @Test
    void 한_연결을_닫지_못해도_나머지_종료를_계속한다() throws Exception {
        manager.register(session);
        when(session.isOpen()).thenReturn(true);
        WebSocketSession another = mock(WebSocketSession.class);
        when(another.getId()).thenReturn("shutdown-b");
        var principal = session.getPrincipal();
        when(another.getPrincipal()).thenReturn(principal);
        when(another.isOpen()).thenReturn(true);
        manager.register(another);
        doThrow(new IOException("test shutdown close failure"))
                .when(session).close(CloseStatus.SERVICE_RESTARTED);
        doThrow(new IllegalStateException("test shutdown close failure"))
                .when(another).close(CloseStatus.SERVICE_RESTARTED);

        manager.shutdownConnections();

        verify(session).close(CloseStatus.SERVICE_RESTARTED);
        verify(another).close(CloseStatus.SERVICE_RESTARTED);
        assertThat(manager.canAcceptConnection()).isFalse();
    }

    @Test
    void 연결의_액세스_토큰_만료_시각에_종료를_예약한다() {
        assertThat(manager.register(session)).isTrue();

        verify(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        assertThat(manager.getSessions()).containsExactly(session);
    }

    @Test
    void 구독이_사용_불가이면_만료_예약_없이_등록을_거절한다() {
        subscriptionState.markUnavailable();

        assertThat(manager.register(session)).isFalse();

        assertThat(manager.getSessions()).isEmpty();
        verifyNoInteractions(scheduler);
    }

    @Test
    void 구독이_복구되면_연결_등록을_허용한다() {
        subscriptionState.markUnavailable();
        assertThat(manager.register(session)).isFalse();
        subscriptionState.markSubscribed();

        assertThat(manager.register(session)).isTrue();

        assertThat(manager.getSessions()).containsExactly(session);
        verify(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
    }

    @Test
    void 상한을_넘는_연결은_거절하고_기존_연결_종료_후_허용한다() throws Exception {
        var limited = new WebSocketSessionManager(scheduler, subscriptionState,
                new WebSocketProperties(List.of(), 1));
        WebSocketSession another = mock(WebSocketSession.class);
        assertThat(limited.register(session)).isTrue();

        assertThat(limited.register(another)).isFalse();
        assertThat(limited.getSessions()).containsExactly(session);
        verify(session, never()).close(any(CloseStatus.class));
        verifyNoInteractions(another);
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));

        limited.unregister("connection-a");
        when(another.getId()).thenReturn("connection-b");
        var principal = session.getPrincipal();
        when(another.getPrincipal()).thenReturn(principal);

        assertThat(limited.register(another)).isTrue();
        assertThat(limited.getSessions()).containsExactly(another);
    }

    @Test
    void 동시_등록에서도_설정된_연결_상한을_지킨다() throws Exception {
        var limited = new WebSocketSessionManager(scheduler, subscriptionState,
                new WebSocketProperties(List.of(), 3));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> registrations = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            try {
                var principal = session.getPrincipal();
                for (int i = 0; i < 20; i++) {
                    WebSocketSession candidate = mock(WebSocketSession.class);
                    when(candidate.getId()).thenReturn("concurrent-" + i);
                    when(candidate.getPrincipal()).thenReturn(principal);
                    registrations.add(executor.submit(() -> {
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        return limited.register(candidate);
                    }));
                }
                start.countDown();
                int accepted = 0;
                for (Future<Boolean> registration : registrations) {
                    if (registration.get(5, TimeUnit.SECONDS)) accepted++;
                }
                assertThat(accepted).isEqualTo(3);
                assertThat(limited.getSessions()).hasSize(3);
                verify(scheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));
            } finally {
                start.countDown();
            }
        }
    }

    @Test
    void 연결이_먼저_닫히면_만료_예약을_취소한다() {
        manager.register(session);

        manager.unregister("connection-a");

        verify(reservation).cancel(false);
        assertThat(session.getAttributes()).doesNotContainKey(WebSocketSessionCredentials.ATTRIBUTE_NAME);
        assertThat(manager.getSessions()).isEmpty();
    }

    @Test
    void 만료된_연결을_닫고_종료_콜백에서_제거한다() throws Exception {
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
    void 예약_작업이_시작돼도_이미_제거된_연결은_건너뛴다() throws Exception {
        manager.register(session);
        verify(scheduler).schedule(task.capture(), eq(EXPIRES_AT));
        manager.unregister("connection-a");

        task.getValue().run();

        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void 예약_실패_시_연결을_제거하고_예외를_전파한다() {
        RejectedExecutionException failure = new RejectedExecutionException("test rejection");
        doThrow(failure).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        assertThatThrownBy(() -> manager.register(session)).isSameAs(failure);
        assertThat(manager.getSessions()).isEmpty();
    }

    @Test
    void 연결_종료가_실패하면_등록을_유지한다() throws Exception {
        manager.register(session);
        verify(scheduler).schedule(task.capture(), eq(EXPIRES_AT));
        doThrow(new IOException("test close failure")).when(session).close(CloseStatus.POLICY_VIOLATION);

        task.getValue().run();

        assertThat(manager.getSessions()).containsExactly(session);
    }

    @Test
    void 연결_ID가_아닌_로그인_세션으로_연결을_묶는다() {
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
    void 예약_작업_실행_전에_확보한_연결만_종료한다() throws Exception {
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
    void 작업_실행_시_이미_닫힌_연결은_건너뛴다() throws Exception {
        manager.register(session);
        when(session.isOpen()).thenReturn(false);
        clearInvocations(scheduler);

        manager.closeConnectionsForRedisFailure();
        verify(scheduler).schedule(task.capture(), any(Instant.class));
        task.getValue().run();

        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void 한_연결의_종료가_실패해도_나머지를_계속_닫는다() throws Exception {
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
    void 종료_콜백에서_자격_증명과_만료_예약을_제거한다() throws Exception {
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
