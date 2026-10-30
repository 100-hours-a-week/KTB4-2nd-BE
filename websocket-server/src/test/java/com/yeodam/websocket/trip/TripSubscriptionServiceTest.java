package com.yeodam.websocket.trip;

import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.trip.client.BackendTripEditClient;
import com.yeodam.websocket.trip.service.TripSubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class TripSubscriptionServiceTest {

    private final BackendTripEditClient client = mock(BackendTripEditClient.class);
    private final WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final TripSubscriptionService service = new TripSubscriptionService(client, sessions, scheduler);
    private final WebSocketSessionCredentials credentials = new WebSocketSessionCredentials("access", "context");
    private final Authentication user = UsernamePasswordAuthenticationToken.authenticated(
            new WebSocketPrincipal(42L, "login", Instant.now().plusSeconds(300)), null, List.of()
    );

    @org.junit.jupiter.api.BeforeEach
    void allowAccess() {
        when(client.checkAccess(anyLong(), anyLong(), any())).thenAnswer(invocation -> Instant.now().plusSeconds(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/trips/0/events", "/topic/trips/-1/events", "/topic/trips/01/events", "/topic/trips/*/events",
            "/topic/trips/9223372036854775808/events", "/topic/trips/14/events/", "/topic/trips/14/events?x=1", "/user/99/queue/command-results"})
    void 잘못되거나_신원을_위조한_목적지를_거절한다(String destination) {
        assertThat(service.authorize(message("s1", "sub", destination, SimpMessageType.SUBSCRIBE), user)).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void 새_구독은_매번_검사하고_주기_검사는_사용자와_여행별로_합친다() {
        subscribe("s1");
        subscribe("s2");
        List<WebSocketSession> connections = List.of(connection("s1"), connection("s2"));
        when(sessions.getSessions()).thenReturn(connections);
        clearInvocations(client);
        service.revalidateSubscriptions();
        verify(client, times(1)).checkAccess(14, 42, credentials);
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 권한_회수나_BE_실패_시_관련_연결을_모두_닫는다() {
        subscribe("s1");
        subscribe("s2");
        doThrow(new IllegalStateException("unavailable")).when(client).checkAccess(14, 42, credentials);
        service.revalidate(42, 14);
        verify(sessions).closeConnection("s1");
        verify(sessions).closeConnection("s2");
        clearInvocations(client);
        service.revalidate(42, 14);
        verifyNoInteractions(client);
    }

    @Test
    void 구독_해제와_연결_종료_시_구독을_제거한다() {
        subscribe("s1");
        subscribe("s2");
        service.onUnsubscribe(new SessionUnsubscribeEvent(this, message("s1", "sub", null, SimpMessageType.UNSUBSCRIBE)));
        service.onDisconnect(new SessionDisconnectEvent(this, message("s2", "sub", null, SimpMessageType.DISCONNECT), "s2", CloseStatus.NORMAL));
        clearInvocations(client);
        service.revalidate(42, 14);
        verifyNoInteractions(client);
    }

    @Test
    void 접근_검사가_실패하면_구독을_등록하지_않는다() {
        doThrow(new IllegalStateException()).when(client).checkAccess(14, 42, credentials);
        assertThat(service.authorize(message("s1", "sub", "/topic/trips/14/events", SimpMessageType.SUBSCRIBE), user)).isFalse();
        clearInvocations(client);
        service.revalidate(42, 14);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/app/trips/14/update", "/app/trips/14/attachments/delete"})
    void 명령_목적지만_허용하고_읽기_권한을_부여하지_않는다(String destination) {
        assertThat(service.authorize(message("s1", "sub", destination, SimpMessageType.MESSAGE), user)).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    void 주기_재검증_전이라도_유효기한이_지나면_여행_전송을_차단한다() {
        when(client.checkAccess(14, 42, credentials)).thenReturn(Instant.now().minusSeconds(1));
        subscribe("s1");
        Message<byte[]> outgoing = message("s1", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE);
        assertThat(service.preSend(outgoing, mock(org.springframework.messaging.MessageChannel.class))).isNull();
        verify(sessions).closeConnection("s1");
    }

    @Test
    void 재검증이_성공하면_읽기_전송을_유지한다() {
        subscribe("s1");
        service.revalidate(42, 14);
        Message<byte[]> outgoing = message("s1", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE);
        assertThat(service.preSend(outgoing, mock(org.springframework.messaging.MessageChannel.class))).isSameAs(outgoing);
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 핸들러_실행_전_권한이_만료되면_대기_여행_이벤트를_버린다() {
        Instant validUntil = Instant.now().plusSeconds(2);
        List<Runnable> queue = new ArrayList<>();
        MessageHandler handler = mock(MessageHandler.class);
        ExecutorSubscribableChannel channel = queuedChannel(queue, handler);
        when(client.checkAccess(14, 42, credentials)).thenReturn(validUntil);

        subscribe("s1");
        channel.send(message("s1", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE));
        assertThat(queue).hasSize(1);

        await().atMost(Duration.ofSeconds(5)).until(() -> !Instant.now().isBefore(validUntil));
        queue.forEach(Runnable::run);

        verifyNoInteractions(handler);
        verify(sessions).closeConnection("s1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unsubscribe", "revocation"})
    void 구독이_제거되면_대기_여행_이벤트를_버린다(String cause) {
        subscribe("s1");
        List<Runnable> queue = new ArrayList<>();
        MessageHandler handler = mock(MessageHandler.class);
        ExecutorSubscribableChannel channel = queuedChannel(queue, handler);
        channel.send(message("s1", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE));
        assertThat(queue).hasSize(1);

        if ("unsubscribe".equals(cause)) {
            service.onUnsubscribe(new SessionUnsubscribeEvent(this,
                    message("s1", "sub", null, SimpMessageType.UNSUBSCRIBE)));
        } else {
            doThrow(new IllegalStateException("revoked")).when(client).checkAccess(14, 42, credentials);
            service.revalidate(42, 14);
        }
        queue.forEach(Runnable::run);

        verifyNoInteractions(handler);
        if ("unsubscribe".equals(cause)) {
            verify(sessions, never()).closeConnection(anyString());
        } else {
            verify(sessions).closeConnection("s1");
        }
    }

    @Test
    void 현재_읽기_권한이_있으면_대기_여행_이벤트를_전달한다() {
        subscribe("s1");
        List<Runnable> queue = new ArrayList<>();
        MessageHandler handler = mock(MessageHandler.class);
        ExecutorSubscribableChannel channel = queuedChannel(queue, handler);
        Message<byte[]> outgoing = message("s1", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE);

        channel.send(outgoing);
        queue.forEach(Runnable::run);

        verify(handler).handleMessage(outgoing);
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 대기_개인_결과는_기존_사용자_라우팅으로_전달한다() {
        List<Runnable> queue = new ArrayList<>();
        MessageHandler handler = mock(MessageHandler.class);
        ExecutorSubscribableChannel channel = queuedChannel(queue, handler);
        Message<byte[]> outgoing = message("s1", "personal", "/queue/command-results-user-s1", SimpMessageType.MESSAGE);

        channel.send(outgoing);
        queue.forEach(Runnable::run);

        verify(handler).handleMessage(outgoing);
        verifyNoInteractions(client, sessions);
    }

    @Test
    void 활성_세션_스냅샷_이후_등록된_새_구독을_보존한다() {
        when(sessions.getSessions()).thenAnswer(invocation -> {
            subscribe("new-session");
            return List.of();
        });

        service.revalidateSubscriptions();

        Message<byte[]> outgoing = message("new-session", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE);
        assertThat(service.preSend(outgoing, new ExecutorSubscribableChannel())).isSameAs(outgoing);
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 이전_스냅샷_정리_중_교체된_구독을_보존한다() {
        Instant previousLease = Instant.now().plusSeconds(20);
        // 공유 만료 시각이 가까우면 재구독도 같은 기한을 받는다.
        when(client.checkAccess(14, 42, credentials)).thenReturn(previousLease);
        subscribe("s1");
        when(sessions.getSessions()).thenAnswer(invocation -> {
            subscribe("s1");
            return List.of();
        });

        service.revalidateSubscriptions();

        Message<byte[]> outgoing = message("s1", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE);
        assertThat(service.preSend(outgoing, new ExecutorSubscribableChannel())).isSameAs(outgoing);
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 비활성_세션의_구독은_정리한다() {
        subscribe("s1");
        when(sessions.getSessions()).thenReturn(List.of());
        clearInvocations(client);

        service.revalidateSubscriptions();
        service.revalidate(42, 14);

        verifyNoInteractions(client);
        Message<byte[]> outgoing = message("s1", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE);
        assertThat(service.preSend(outgoing, new ExecutorSubscribableChannel())).isNull();
        verify(sessions, never()).closeConnection(anyString());
    }

    @Test
    void 교체된_구독을_닫지_않고_이전_대기_여행_이벤트만_버린다() {
        subscribe("s1");
        List<Runnable> queue = new ArrayList<>();
        MessageHandler handler = mock(MessageHandler.class);
        ExecutorSubscribableChannel channel = queuedChannel(queue, handler);
        channel.send(message("s1", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE));
        service.onUnsubscribe(new SessionUnsubscribeEvent(this,
                message("s1", "sub", null, SimpMessageType.UNSUBSCRIBE)));
        assertThat(service.authorize(message("s1", "sub", "/topic/trips/15/events", SimpMessageType.SUBSCRIBE), user)).isTrue();

        queue.forEach(Runnable::run);

        verifyNoInteractions(handler);
        verify(sessions, never()).closeConnection(anyString());
        Message<byte[]> currentTrip = message("s1", "sub", "/topic/trips/15/events", SimpMessageType.MESSAGE);
        assertThat(service.preSend(currentTrip, channel)).isSameAs(currentTrip);
    }

    @Test
    void 오래된_검사_실패로_교체된_구독을_닫지_않는다() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Instant sharedExpiry = Instant.now().plusSeconds(30);
        when(client.checkAccess(14, 42, credentials)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                service.onUnsubscribe(new SessionUnsubscribeEvent(this,
                        message("s1", "sub", null, SimpMessageType.UNSUBSCRIBE)));
                subscribe("s1");
                throw new IllegalStateException("old credentials failed");
            }
            return sharedExpiry;
        });
        subscribe("s1");

        service.revalidate(42, 14);

        Message<byte[]> outgoing = message("s1", "sub", "/topic/trips/14/events", SimpMessageType.MESSAGE);
        assertThat(service.preSend(outgoing, new ExecutorSubscribableChannel())).isSameAs(outgoing);
        verify(sessions, never()).closeConnection(anyString());
    }

    private ExecutorSubscribableChannel queuedChannel(List<Runnable> queue, MessageHandler handler) {
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel(queue::add);
        channel.addInterceptor(service);
        channel.subscribe(handler);
        return channel;
    }

    private void subscribe(String sessionId) {
        assertThat(service.authorize(message(sessionId, "sub", "/topic/trips/14/events", SimpMessageType.SUBSCRIBE), user)).isTrue();
    }

    private WebSocketSession connection(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    private Message<byte[]> message(String sessionId, String subscriptionId, String destination, SimpMessageType type) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(type);
        headers.setSessionId(sessionId);
        headers.setSubscriptionId(subscriptionId);
        headers.setDestination(destination);
        headers.setSessionAttributes(Map.of(WebSocketSessionCredentials.ATTRIBUTE_NAME, credentials));
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
