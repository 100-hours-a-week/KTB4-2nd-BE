package com.yeodam.websocket.common.websocket.session;

import com.yeodam.websocket.common.websocket.config.WebSocketProperties;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class WebSocketRedisFailureConcurrencyTest {

    private static final Instant EXPIRES_AT = Instant.parse("2099-01-01T00:00:00Z");
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final RedisSubscriptionState state = new RedisSubscriptionState();
    private final WebSocketSessionManager manager = new WebSocketSessionManager(scheduler, state, new WebSocketProperties(java.util.List.of(), 1000));
    private final ScheduledFuture<?> reservation = mock(ScheduledFuture.class);

    @BeforeEach
    void prepareSubscription() {
        state.markSubscribed();
        doReturn(reservation).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void 장애_발생_당시_등록_중인_연결도_종료_대상에_포함한다() throws Exception {
        WebSocketSession session = session("registering");
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Runnable> closeTask = new AtomicReference<>();
        AtomicReference<Thread> failureThread = new AtomicReference<>();
        doAnswer(invocation -> {
            if (EXPIRES_AT.equals(invocation.getArgument(1))) {
                registering.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } else {
                closeTask.set(invocation.getArgument(0));
            }
            return reservation;
        }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var registered = executor.submit(() -> manager.register(session));
            try {
                assertThat(registering.await(5, TimeUnit.SECONDS)).isTrue();
                var failed = executor.submit(() -> {
                    failureThread.set(Thread.currentThread());
                    manager.closeConnectionsForRedisFailure();
                });
                await().atMost(Duration.ofSeconds(5)).until(() ->
                        failureThread.get() != null && failureThread.get().getState() == Thread.State.BLOCKED);
                assertThat(state.isReady()).isTrue();

                release.countDown();
                assertThat(registered.get(5, TimeUnit.SECONDS)).isTrue();
                failed.get(5, TimeUnit.SECONDS);
                assertThat(state.isReady()).isFalse();
                closeTask.get().run();
                verify(session).close(CloseStatus.SERVER_ERROR);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void 이전_장애의_종료_작업에서_복구_후의_연결을_제외한다() throws Exception {
        WebSocketSession existing = session("existing");
        WebSocketSession later = session("after-recovery");
        assertThat(manager.register(existing)).isTrue();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Runnable> closeTask = new AtomicReference<>();
        AtomicReference<Thread> registrationThread = new AtomicReference<>();
        doAnswer(invocation -> {
            if (!EXPIRES_AT.equals(invocation.getArgument(1))) {
                closeTask.set(invocation.getArgument(0));
                closing.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return reservation;
        }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var failed = executor.submit(manager::closeConnectionsForRedisFailure);
            try {
                assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(state.isReady()).isFalse();
                state.markSubscribed();
                var registered = executor.submit(() -> {
                    registrationThread.set(Thread.currentThread());
                    return manager.register(later);
                });
                await().atMost(Duration.ofSeconds(5)).until(() ->
                        registrationThread.get() != null && registrationThread.get().getState() == Thread.State.BLOCKED);

                release.countDown();
                failed.get(5, TimeUnit.SECONDS);
                assertThat(registered.get(5, TimeUnit.SECONDS)).isTrue();
                closeTask.get().run();
                verify(existing).close(CloseStatus.SERVER_ERROR);
                verify(later, never()).close(any(CloseStatus.class));
            } finally {
                release.countDown();
            }
        }
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new WebSocketPrincipal(42L, "login-session", EXPIRES_AT), null, List.of()
        ));
        return session;
    }
}
