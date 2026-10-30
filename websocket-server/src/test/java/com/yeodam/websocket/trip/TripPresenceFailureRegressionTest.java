package com.yeodam.websocket.trip;

import com.yeodam.websocket.RedisTestSupport;
import com.yeodam.websocket.common.websocket.config.WebSocketEventProperties;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.trip.client.TripPresenceAccessClient;
import com.yeodam.websocket.trip.client.response.TripPresenceAccessResponse.AccessData;
import com.yeodam.websocket.trip.config.TripPresenceMessagingConfig;
import com.yeodam.websocket.trip.config.TripPresenceProperties;
import com.yeodam.websocket.trip.repository.TripPresenceRepository.ConnectionPresence;
import com.yeodam.websocket.trip.repository.TripPresenceRepository.PresenceChange;
import com.yeodam.websocket.trip.repository.TripPresenceRepository;
import com.yeodam.websocket.trip.service.TripPresenceService;
import com.yeodam.websocket.trip.service.TripPresenceSessionRegistry;
import com.yeodam.websocket.trip.service.response.TripPresenceSnapshotResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class TripPresenceFailureRegressionTest extends RedisTestSupport {
    private LettuceConnectionFactory factory;
    private final ExecutorService accessExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService cleanupExecutor = Executors.newFixedThreadPool(2);
    private final TripPresenceSessionRegistry registry = new TripPresenceSessionRegistry();
    private final TripPresenceAccessClient client = mock(TripPresenceAccessClient.class);
    private final WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @AfterEach
    void 테스트_자원_종료() {
        accessExecutor.shutdownNow();
        cleanupExecutor.shutdownNow();
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void 권한_조회가_느려도_병렬_갱신으로_정상_참여자를_유지한다() throws Exception {
        TripPresenceRepository repository = 실제_Redis_저장소_생성();
        TripPresenceService service = 참여_서비스_생성(repository);
        String instanceId = (String) ReflectionTestUtils.getField(service, "instanceId");
        Instant now = Instant.now();
        Instant originalDeadline = now.plusMillis(2000);
        for (long index = 1; index <= 4; index++) {
            String id = "socket-" + index;
            AccessData access = new AccessData(10L, index, "회원", null, "OWNER",
                    originalDeadline.minusSeconds(30), originalDeadline, null);
            연결과_구독_등록(id, index, access);
            repository.join(10L, new ConnectionPresence(instanceId + ":" + id, index, "회원", null,
                    "OWNER", access.checkedAt(), access.accessValidUntil(), now.plusSeconds(300)));
        }
        when(client.findPresenceAccess(eq(10L), anyLong(), any())).thenAnswer(invocation -> {
            Thread.sleep(600);
            Instant checkedAt = Instant.now();
            return new AccessData(10L, invocation.getArgument(1), "회원", null, "OWNER", checkedAt,
                    checkedAt.plusSeconds(30), null);
        });
        service.revalidateAccess();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(client, times(4)).findPresenceAccess(eq(10L), anyLong(), any()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(registry.connections()).allSatisfy(connection ->
                        assertThat(connection.access().get(10L).accessValidUntil()).isAfter(originalDeadline)));
        int present = repository.findSnapshot(10L).snapshot().members().size();
        assertThat(present).isEqualTo(4);
        assertThat(registry.connections()).hasSize(4);
        service.closeCursor();
    }

    @Test
    void 연결별_미완료_명령_상한을_초과하면_연결을_종료한다() {
        Executor stalledExecutor = (Runnable task) -> {
        };
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel(stalledExecutor);
        OrderedMessageChannelDecorator.configureInterceptor(channel, true);
        channel.subscribe((Message<?> message) -> {
        });
        TripPresenceMessagingConfig config = new TripPresenceMessagingConfig(참여_서비스_생성(mock(TripPresenceRepository.class)));
        TestChannelRegistration registration = new TestChannelRegistration();
        config.configureClientInboundChannel(registration);
        for (ChannelInterceptor interceptor : registration.인터셉터_목록()) {
            channel.addInterceptor(interceptor);
        }
        Map<String, Object> attributes = new HashMap<>();
        for (int index = 0; index < 100; index++) {
            channel.send(체류_갱신_명령_생성(attributes));
        }
        verify(sessions).closeConnection("socket");
    }

    @Test
    void 연결_종료는_Redis를_기다리지_않고_로컬_권한을_해제한다() throws Exception {
        TripPresenceRepository repository = mock(TripPresenceRepository.class);
        Instant now = Instant.now();
        for (long tripId = 1; tripId <= 3; tripId++) {
            registry.subscribe(registry.connection("socket"), "sub-" + tripId, tripId,
                    new AccessData(tripId, 1L, "회원", null, "OWNER", now, now.plusSeconds(30), null));
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.leave(anyLong(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new PresenceChange(false, new TripPresenceSnapshotResponse(
                    invocation.getArgument(0), "epoch", 0L, List.of()));
        });
        TripPresenceService service = 참여_서비스_생성(repository);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> disconnect = executor.submit(() -> service.onDisconnected(new SessionDisconnectEvent(
                    this, MessageBuilder.withPayload(new byte[0]).build(), "socket", CloseStatus.NORMAL)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            disconnect.get(1, TimeUnit.SECONDS);
            assertThat(registry.connections()).isEmpty();
            release.countDown();
            disconnect.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(repository, times(3)).leave(anyLong(), anyString()));
    }

    @Test
    void 체류_갱신은_해당_연결만_저장한다() {
        factory = new LettuceConnectionFactory(redisHost(), redisPort());
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        TripPresenceRepository repository = new TripPresenceRepository(redis, mapper,
                new TripPresenceProperties("probe:" + UUID.randomUUID(), 16, 100, 10));
        Instant now = Instant.now();
        for (int index = 0; index < 100; index++) {
            repository.join(20L, new ConnectionPresence("socket-" + index, 1L, "회원", null,
                    "OWNER", now, now.plusSeconds(30), now.plusSeconds(300)));
        }
        long beforeHset = Redis_명령_호출_수(redis, "hset");
        long beforeZadd = Redis_명령_호출_수(redis, "zadd");
        repository.heartbeat(20L, "socket-0");
        long hsets = Redis_명령_호출_수(redis, "hset") - beforeHset;
        long zadds = Redis_명령_호출_수(redis, "zadd") - beforeZadd;
        assertThat(hsets).isEqualTo(1);
        assertThat(zadds).isEqualTo(1);
    }

    @Test
    void 모든_핸들러가_명령을_완료하면_대기_슬롯을_반환한다() {
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
        OrderedMessageChannelDecorator.configureInterceptor(channel, true);
        AtomicInteger handled = new AtomicInteger();
        channel.subscribe(message -> handled.incrementAndGet());
        channel.subscribe(message -> handled.incrementAndGet());
        TripPresenceMessagingConfig config = new TripPresenceMessagingConfig(참여_서비스_생성(mock(TripPresenceRepository.class)));
        TestChannelRegistration registration = new TestChannelRegistration();
        config.configureClientInboundChannel(registration);
        registration.인터셉터_목록().forEach(channel::addInterceptor);
        Map<String, Object> attributes = new HashMap<>();
        for (int index = 0; index < 30; index++) {
            channel.send(체류_갱신_명령_생성(attributes));
        }
        assertThat(handled.get()).isEqualTo(60);
        verifyNoInteractions(sessions);
    }

    @Test
    void 진행_중인_권한_갱신은_중복되지_않고_참여_정리를_막지_않는다() throws Exception {
        TripPresenceRepository repository = mock(TripPresenceRepository.class);
        Instant now = Instant.now();
        연결과_구독_등록("socket", 1L, new AccessData(10L, 1L, "회원", null, "OWNER",
                now.minusSeconds(25), now.plusSeconds(5), null));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.findPresenceAccess(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            Instant checkedAt = Instant.now();
            return new AccessData(10L, 1L, "회원", null, "OWNER", checkedAt, checkedAt.plusSeconds(30), null);
        });
        PresenceChange unchanged = new PresenceChange(false,
                new TripPresenceSnapshotResponse(10L, "epoch", 0L, List.of()));
        when(repository.refreshAccess(anyLong(), any())).thenReturn(unchanged);
        when(repository.findSnapshot(10L)).thenReturn(unchanged);
        when(repository.findEpoch(10L)).thenReturn("epoch");
        when(repository.scanActiveTrips(anyInt())).thenAnswer(invocation ->
                mock(org.springframework.data.redis.core.Cursor.class));
        TripPresenceService service = 참여_서비스_생성(repository);
        try {
            service.revalidateAccess();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.revalidateAccess();
            service.maintainParticipations();
            verify(client).findPresenceAccess(eq(10L), eq(1L), any());
            verify(repository).findSnapshot(10L);
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(registry.connection("socket").access().get(10L).accessValidUntil())
                        .isAfter(now.plusSeconds(5)));
        service.closeCursor();
    }

    @Test
    void 제출이_거절된_권한_갱신은_다음_주기에_재시도한다() {
        TripPresenceRepository repository = mock(TripPresenceRepository.class);
        Instant now = Instant.now();
        연결과_구독_등록("socket", 1L, new AccessData(10L, 1L, "회원", null, "OWNER",
                now.minusSeconds(25), now.plusSeconds(5), null));
        when(client.findPresenceAccess(anyLong(), anyLong(), any())).thenReturn(
                new AccessData(10L, 1L, "회원", null, "OWNER", now, now.plusSeconds(30), null));
        when(repository.refreshAccess(anyLong(), any())).thenReturn(new PresenceChange(false,
                new TripPresenceSnapshotResponse(10L, "epoch", 0L, List.of())));
        AtomicInteger attempts = new AtomicInteger();
        Executor rejectFirst = task -> {
            if (attempts.getAndIncrement() == 0) {
                throw new RejectedExecutionException("full");
            }
            task.run();
        };
        TripPresenceService service = 참여_서비스_생성(repository, rejectFirst, cleanupExecutor);
        service.revalidateAccess();
        verifyNoInteractions(client);
        service.revalidateAccess();
        assertThat(registry.connection("socket").access().get(10L).accessValidUntil())
                .isEqualTo(now.plusSeconds(30));
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 정리_제출이_거절돼도_수신을_차단하고_기한으로_참여자를_제거한다() {
        TripPresenceRepository repository = 실제_Redis_저장소_생성();
        Executor rejectCleanup = task -> {
            throw new RejectedExecutionException("full");
        };
        TripPresenceService service = 참여_서비스_생성(repository, accessExecutor, rejectCleanup);
        Instant now = Instant.now();
        AccessData access = new AccessData(10L, 1L, "회원", null, "OWNER", now, now.plusMillis(800), null);
        연결과_구독_등록("socket", 1L, access);
        String instanceId = (String) ReflectionTestUtils.getField(service, "instanceId");
        repository.join(10L, new ConnectionPresence(instanceId + ":socket", 1L, "회원", null,
                "OWNER", now, access.accessValidUntil(), now.plusSeconds(300)));
        registry.acceptEpoch(10L, repository.findEpoch(10L));
        service.onDisconnected(new SessionDisconnectEvent(this,
                MessageBuilder.withPayload(new byte[0]).build(), "socket", CloseStatus.NORMAL));
        assertThat(registry.canDeliver("socket", "sub", 10L, repository.findEpoch(10L), Instant.now())).isFalse();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.expire(10L).snapshot().members()).isEmpty());
    }

    @Test
    void 거절된_연결은_소켓_종료_전에_참여_상태를_재생성하지_못한다() {
        TripPresenceRepository repository = mock(TripPresenceRepository.class);
        Instant now = Instant.now();
        연결과_구독_등록("socket", 1L, new AccessData(10L, 1L, "회원", null, "OWNER",
                now, now.plusSeconds(30), null));
        WebSocketSession session = sessions.findSession("socket");
        TripPresenceService service = 참여_서비스_생성(repository, accessExecutor, (Runnable task) -> {
        });
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setSessionId("socket");
        headers.setUser(session.getPrincipal());
        Message<byte[]> command = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        service.rejectCommand(command, new com.yeodam.websocket.trip.exception.TripPresenceException("INVALID_REQUEST"));
        assertThatThrownBy(() -> service.join("10", command)).hasMessage("UNAUTHORIZED");
        assertThat(registry.connections()).isEmpty();
        verifyNoInteractions(client, repository);
    }

    @Test
    void 첫_SEND는_처리_중인_다른_메시지의_인터셉터_순회를_변경하지_않는다() throws Exception {
        CountDownLatch handling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel(executor);
            context.registerBean(TripPresenceService.class, () -> mock(TripPresenceService.class));
            context.registerBean("clientInboundChannel", ExecutorSubscribableChannel.class, () -> channel);
            context.register(TripPresenceMessagingConfig.class);
            context.refresh();
            channel.addInterceptor(new ExecutorChannelInterceptor() {
                @Override
                public Message<?> beforeHandle(Message<?> message, MessageChannel target, MessageHandler handler) {
                    if (StompHeaderAccessor.wrap(message).getCommand() == StompCommand.CONNECT) {
                        handling.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("SEND 대기 시간 초과");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                    }
                    return message;
                }
            });
            TestChannelRegistration registration = new TestChannelRegistration();
            context.getBean(TripPresenceMessagingConfig.class).configureClientInboundChannel(registration);
            registration.인터셉터_목록().forEach(channel::addInterceptor);
            channel.subscribe(message -> {
                if (StompHeaderAccessor.wrap(message).getCommand() == StompCommand.CONNECT) {
                    connected.countDown();
                }
            });
            StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.CONNECT);
            channel.send(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
            assertThat(handling.await(2, TimeUnit.SECONDS)).isTrue();
            channel.send(체류_갱신_명령_생성(new HashMap<>()));
            release.countDown();
            assertThat(connected.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 전송_직전에_구독_권한의_유효기간을_확인한다(boolean expired) {
        Instant now = Instant.now();
        Instant checkedAt = expired ? now.minusSeconds(60) : now;
        AccessData access = new AccessData(10L, 1L, "회원", null, "OWNER", checkedAt,
                expired ? now.minusSeconds(30) : now.plusSeconds(30), null);
        연결과_구독_등록("socket", 1L, access);
        registry.acceptEpoch(10L, "epoch");
        TripPresenceRepository repository = mock(TripPresenceRepository.class);
        TripPresenceMessagingConfig config = new TripPresenceMessagingConfig(참여_서비스_생성(repository));
        TestChannelRegistration registration = new TestChannelRegistration();
        config.configureClientOutboundChannel(registration);
        ExecutorChannelInterceptor guard = (ExecutorChannelInterceptor) registration.인터셉터_목록().getFirst();
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
        headers.setSessionId("socket");
        headers.setSubscriptionId("sub");
        headers.setDestination("/topic/trips/10/presence");
        Message<byte[]> message = MessageBuilder.createMessage(mapper.writeValueAsBytes(Map.of(
                "message", "PRESENCE_SNAPSHOT", "data", Map.of("tripId", 10L, "epoch", "epoch"))),
                headers.getMessageHeaders());

        Message<?> delivered = guard.beforeHandle(message, mock(MessageChannel.class), mock(MessageHandler.class));

        assertThat(delivered).isEqualTo(expired ? null : message);
        verifyNoInteractions(client, repository);
    }

    @Test
    void 원래_채널의_실패는_기존_STOMP_오류_처리기에_위임한다() {
        TripPresenceService service = mock(TripPresenceService.class);
        TripPresenceMessagingConfig config = new TripPresenceMessagingConfig(service);
        TestChannelRegistration registration = new TestChannelRegistration();
        config.configureClientInboundChannel(registration);
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        headers.setSessionId("socket");
        headers.setSubscriptionId("forbidden");
        headers.setDestination("/user/7/queue/ai-tasks");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        registration.인터셉터_목록().getFirst().afterSendCompletion(message,
                mock(MessageChannel.class), false, new org.springframework.security.access.AccessDeniedException("denied"));
        verify(service).unsubscribe("socket", "forbidden");
        verify(service, never()).rejectCommand(any(), any());
    }

    private Message<byte[]> 체류_갱신_명령_생성(Map<String, Object> attributes) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setSessionId("socket");
        headers.setSessionAttributes(attributes);
        headers.setDestination("/app/trips/10/presence/heartbeat");
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static final class TestChannelRegistration extends ChannelRegistration {
        List<ChannelInterceptor> 인터셉터_목록() {
            return getInterceptors();
        }
    }

    private long Redis_명령_호출_수(StringRedisTemplate redis, String command) {
        Properties info = redis.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().info("commandstats"));
        String stat = info.getProperty("cmdstat_" + command, "calls=0");
        return Long.parseLong(stat.substring("calls=".length()).split(",")[0]);
    }

    private TripPresenceRepository 실제_Redis_저장소_생성() {
        factory = new LettuceConnectionFactory(redisHost(), redisPort());
        factory.afterPropertiesSet();
        return new TripPresenceRepository(new StringRedisTemplate(factory), mapper,
                new TripPresenceProperties("probe:" + UUID.randomUUID(), 16, 100, 10));
    }

    private TripPresenceService 참여_서비스_생성(TripPresenceRepository repository) {
        return 참여_서비스_생성(repository, accessExecutor, cleanupExecutor);
    }

    private TripPresenceService 참여_서비스_생성(TripPresenceRepository repository, Executor access, Executor cleanup) {
        return new TripPresenceService(registry, client, repository, sessions,
                new TripPresenceProperties("probe", 16, 100, 10), mock(StringRedisTemplate.class),
                new WebSocketEventProperties("probe"), mapper,
                mock(SimpMessagingTemplate.class), mock(MessageChannel.class), access, cleanup);
    }

    private void 연결과_구독_등록(String id, Long userId, AccessData access) {
        WebSocketSession session = mock(WebSocketSession.class);
        WebSocketPrincipal principal = new WebSocketPrincipal(userId, "login-" + id, Instant.now().plusSeconds(300));
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>(Map.of(
                WebSocketSessionCredentials.ATTRIBUTE_NAME, new WebSocketSessionCredentials("access", "context"))));
        when(sessions.findSession(id)).thenReturn(session);
        registry.subscribe(registry.connection(id), "sub", 10L, access);
    }
}
