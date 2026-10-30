package com.yeodam.websocket.common.websocket.security;

import com.yeodam.websocket.RedisTestSupport;
import com.yeodam.websocket.trip.client.BackendTripEditClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "websocket.backend-auth.service-token=application-test-service-token"
)
class WebSocketMessageSecurityTest extends RedisTestSupport {

    @MockitoBean
    private BackendTripEditClient tripClient;

    private static final String CSRF_TOKEN = "existing-test-csrf-token";

    @Autowired
    @Qualifier("clientInboundChannel")
    private AbstractSubscribableChannel clientInboundChannel;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_CONNECT에_기존_CSRF_토큰을_허용한다() {
        assertThat(send(message(SimpMessageType.CONNECT, null, true, CSRF_TOKEN))).isTrue();
    }

    @org.junit.jupiter.api.BeforeEach
    void allowTripAccess() {
        org.mockito.Mockito.when(tripClient.checkAccess(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> Instant.now().plusSeconds(30));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "wrong-csrf-token")
    void CSRF_토큰이_일치하지_않는_CONNECT를_거절한다(String csrfHeader) {
        assertDenied(message(SimpMessageType.CONNECT, null, true, csrfHeader));
    }

    @Test
    void 올바른_CSRF_토큰이_있어도_미인증_CONNECT를_거절한다() {
        assertDenied(message(SimpMessageType.CONNECT, null, false, CSRF_TOKEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user/queue/ai-tasks",
            "/user/queue/command-results"
    })
    void 두_개인_알림_구독을_허용한다(String destination) {
        assertThat(send(message(SimpMessageType.SUBSCRIBE, destination, true, null))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user/7/queue/ai-tasks",
            "/topic/trips/*/events",
            "/queue/ai-tasks",
            "/user/queue/*"
    })
    void 그_외_구독은_거절한다(String destination) {
        assertDenied(message(SimpMessageType.SUBSCRIBE, destination, true, null));
    }

    @Test
    void BE_접근_검사로_여행_구독을_검증한다() {
        assertThat(send(message(SimpMessageType.SUBSCRIBE, "/topic/trips/14/events", true, null))).isTrue();
        org.mockito.Mockito.verify(tripClient).checkAccess(org.mockito.ArgumentMatchers.eq(14L),
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 미인증_개인_구독을_거절한다() {
        assertDenied(message(SimpMessageType.SUBSCRIBE, "/user/queue/ai-tasks", false, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/app/trips/14/update",
            "/app/trips/14/attachments/delete",
            "/topic/trips/14/events",
            "/user/queue/ai-tasks"
    })
    void 클라이언트의_임의_SEND를_거절한다(String destination) {
        assertDenied(message(SimpMessageType.MESSAGE, destination, true, null));
    }

    @ParameterizedTest
    @EnumSource(value = SimpMessageType.class, names = {
            "UNSUBSCRIBE", "DISCONNECT", "HEARTBEAT"
    })
    void 인증된_연결_관리_메시지를_허용한다(SimpMessageType type) {
        assertThat(send(message(type, null, true, null))).isTrue();
    }

    private void assertDenied(Message<?> message) {
        assertThatThrownBy(() -> send(message))
                .isInstanceOf(MessageDeliveryException.class)
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    private boolean send(Message<?> message) {
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
        channel.setInterceptors(clientInboundChannel.getInterceptors());
        channel.subscribe(accepted -> {});
        return channel.send(message);
    }

    private Message<byte[]> message(
            SimpMessageType type,
            String destination,
            boolean authenticated,
            String csrfHeader
    ) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(type);
        headers.setSessionId("message-security-test-session");
        headers.setSubscriptionId("security-subscription");
        headers.setSessionAttributes(Map.of(
                CsrfToken.class.getName(),
                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", CSRF_TOKEN),
                WebSocketSessionCredentials.ATTRIBUTE_NAME,
                new WebSocketSessionCredentials("access", "context")
        ));
        if (authenticated) {
            headers.setUser(UsernamePasswordAuthenticationToken.authenticated(
                    new WebSocketPrincipal(
                            42L, "test-login-session", Instant.parse("2099-01-01T00:00:00Z")
                    ),
                    null,
                    List.of()
            ));
        }
        if (destination != null) {
            headers.setDestination(destination);
        }
        if (csrfHeader != null) {
            headers.setNativeHeader("X-CSRF-TOKEN", csrfHeader);
        }
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
