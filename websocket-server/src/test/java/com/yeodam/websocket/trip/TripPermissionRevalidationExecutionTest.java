package com.yeodam.websocket.trip;

import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.trip.client.BackendTripEditClient;
import com.yeodam.websocket.trip.service.TripSubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripPermissionRevalidationExecutionTest {
    private final BackendTripEditClient client = mock(BackendTripEditClient.class);
    private final WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private final TripSubscriptionService service = new TripSubscriptionService(client, sessions, scheduler, executor);
    private final WebSocketSessionCredentials credentials = new WebSocketSessionCredentials("token", "context");
    private final Authentication user = UsernamePasswordAuthenticationToken.authenticated(
            new WebSocketPrincipal(42L, "login", Instant.now().plusSeconds(300)), null, List.of());

    @BeforeEach
    void setUp() {
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(10);
        executor.initialize();
        when(client.checkAccess(anyLong(), eq(42L), same(credentials)))
                .thenAnswer(invocation -> Instant.now().plusSeconds(60));
    }

    @AfterEach
    void shutDown() {
        service.stopRevalidation();
        executor.shutdown();
    }

    @Test
    void 예약_실행을_막지_않고_주기_검사를_중복하지_않으며_10개씩_병렬_검사한다() throws Exception {
        subscribeGroups(30);
        CountDownLatch started = new CountDownLatch(10);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(30);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(client.checkAccess(anyLong(), eq(42L), same(credentials))).thenAnswer(invocation -> {
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);
            started.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return Instant.now().plusSeconds(60);
            } finally {
                active.decrementAndGet();
                completed.countDown();
            }
        });

        try (ExecutorService schedulingCaller = Executors.newSingleThreadExecutor()) {
            Future<?> dispatch = schedulingCaller.submit(service::revalidateSubscriptions);
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                dispatch.get(500, TimeUnit.MILLISECONDS);
                for (int repeat = 0; repeat < 3; repeat++) {
                    service.revalidateSubscriptions();
                }
            } finally {
                release.countDown();
            }
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum.get()).isEqualTo(10);
            verify(client, times(30)).checkAccess(anyLong(), eq(42L), same(credentials));
        }
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 검사_중_권한_변경은_한_번의_즉시_후속_검사로_합친다() throws Exception {
        subscribeGroups(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(client.checkAccess(1, 42, credentials)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return Instant.now().plusSeconds(60);
        });
        service.revalidateSubscriptions();
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            service.revalidate(42, 1);
            service.revalidate(42, 1);
        } finally {
            release.countDown();
        }
        awaitIdle();
        assertThat(calls.get()).isEqualTo(2);
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 느린_검사_하나가_다른_그룹을_막지_않는다() throws Exception {
        subscribeGroups(20);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch othersCompleted = new CountDownLatch(19);
        when(client.checkAccess(anyLong(), eq(42L), same(credentials))).thenAnswer(invocation -> {
            if (invocation.<Long>getArgument(0) == 1L) {
                slowStarted.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } else {
                othersCompleted.countDown();
            }
            return Instant.now().plusSeconds(60);
        });
        service.revalidateSubscriptions();
        try {
            assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(othersCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        awaitIdle();
        verify(client, times(20)).checkAccess(anyLong(), eq(42L), same(credentials));
    }

    @Test
    void 구독_해제된_대기_그룹은_BE_호출_없이_건너뛴다() throws Exception {
        subscribeGroups(11);
        CountDownLatch started = new CountDownLatch(10);
        CountDownLatch release = new CountDownLatch(1);
        Set<Long> executing = ConcurrentHashMap.newKeySet();
        when(client.checkAccess(anyLong(), eq(42L), same(credentials))).thenAnswer(invocation -> {
            executing.add(invocation.getArgument(0));
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Instant.now().plusSeconds(60);
        });
        service.revalidateSubscriptions();
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            long pendingTrip = java.util.stream.LongStream.rangeClosed(1, 11)
                    .filter(trip -> !executing.contains(trip)).findFirst().orElseThrow();
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
            headers.setSessionId("s" + pendingTrip);
            headers.setSubscriptionId("sub");
            service.onUnsubscribe(new SessionUnsubscribeEvent(this,
                    MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders())));
        } finally {
            release.countDown();
        }
        awaitIdle();
        assertThat(executing).hasSize(10);
        verify(client, times(10)).checkAccess(anyLong(), eq(42L), same(credentials));
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 실패한_그룹의_연결만_종료한다() {
        subscribeGroups(2);
        when(client.checkAccess(1, 42, credentials)).thenThrow(new IllegalStateException("unavailable"));
        service.revalidateSubscriptions();
        awaitIdle();
        verify(sessions).closeConnection("s1");
        verify(sessions, never()).closeConnection("s2");
    }

    @Test
    void 종료_시_대기_검사와_늦은_응답을_버린다() throws Exception {
        subscribeGroups(20);
        CountDownLatch started = new CountDownLatch(10);
        CountDownLatch release = new CountDownLatch(1);
        when(client.checkAccess(anyLong(), eq(42L), same(credentials))).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Instant.now().plusSeconds(60);
        });
        service.revalidateSubscriptions();
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            service.stopRevalidation();
            service.revalidateSubscriptions();
        } finally {
            release.countDown();
        }
        awaitIdle();
        verify(client, times(10)).checkAccess(anyLong(), eq(42L), same(credentials));
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 컨텍스트_종료가_실행기보다_먼저_대기_검사와_늦은_응답을_차단한다() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
             ExecutorService closer = Executors.newSingleThreadExecutor()) {
            context.registerBean("permissionExecutor", ThreadPoolTaskExecutor.class, () -> executor);
            context.registerBean(TripSubscriptionService.class, () -> service);
            context.refresh();
            subscribeGroups(20);
            CountDownLatch started = new CountDownLatch(10);
            CountDownLatch release = new CountDownLatch(1);
            when(client.checkAccess(anyLong(), eq(42L), same(credentials))).thenAnswer(invocation -> {
                started.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return Instant.now().plusSeconds(60);
            });
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setDestination("/app/trips/1/update");
            var command = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
            assertThat(service.authorize(command, user)).isTrue();
            service.revalidateSubscriptions();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> closing = closer.submit(context::close);
            try {
                await().atMost(Duration.ofSeconds(2)).until(() -> !service.authorize(command, user));
            } finally {
                release.countDown();
            }
            closing.get(5, TimeUnit.SECONDS);
            verify(client, times(10)).checkAccess(anyLong(), eq(42L), same(credentials));
            verify(sessions, never()).closeConnection(anyString());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 결과_반영_중_권한이_바뀌어도_후속_검사를_수행한다() {
        subscribeGroups(1);
        Map<Object, Object> stored = (Map<Object, Object>) ReflectionTestUtils.getField(service, "subscriptions");
        Map<Object, Object> observed = spy(stored);
        ReflectionTestUtils.setField(service, "subscriptions", observed);
        AtomicBoolean notified = new AtomicBoolean();
        doAnswer(invocation -> {
            if (notified.compareAndSet(false, true)) {
                service.revalidate(42, 1);
            }
            return invocation.callRealMethod();
        }).when(observed).computeIfPresent(any(), any());

        service.revalidateSubscriptions();
        awaitIdle();

        assertThat(notified).isTrue();
        verify(client, times(2)).checkAccess(1, 42, credentials);
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 검사_중_알림이_도착해도_실패한_현재_등록은_종료한다() throws Exception {
        subscribeGroups(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(client.checkAccess(1, 42, credentials)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                throw new IllegalStateException("verification failed");
            }
            return Instant.now().plusSeconds(60);
        });
        service.revalidateSubscriptions();
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            service.revalidate(42, 1);
        } finally {
            release.countDown();
        }
        awaitIdle();
        verify(sessions).closeConnection("s1");
        assertThat(calls.get()).isEqualTo(1);
    }

    private void awaitIdle() {
        await().atMost(Duration.ofSeconds(5)).until(() -> executor.getActiveCount() == 0
                && executor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private void subscribeGroups(int count) {
        List<WebSocketSession> connected = new ArrayList<>();
        for (long tripId = 1; tripId <= count; tripId++) {
            String sessionId = "s" + tripId;
            WebSocketSession connection = mock(WebSocketSession.class);
            when(connection.getId()).thenReturn(sessionId);
            connected.add(connection);
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
            headers.setSessionId(sessionId);
            headers.setSubscriptionId("sub");
            headers.setDestination("/topic/trips/" + tripId + "/events");
            headers.setSessionAttributes(Map.of(WebSocketSessionCredentials.ATTRIBUTE_NAME, credentials));
            assertThat(service.authorize(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), user)).isTrue();
        }
        when(sessions.getSessions()).thenReturn(connected);
        clearInvocations(client);
    }
}
