package com.yeodam.websocket.common.websocket.security;

import com.yeodam.websocket.RedisTestSupport;
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

    private static final String CSRF_TOKEN = "existing-test-csrf-token";

    @Autowired
    @Qualifier("clientInboundChannel")
    private AbstractSubscribableChannel clientInboundChannel;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsAuthenticatedConnectWithTheOriginalCsrfToken() {
        assertThat(send(message(SimpMessageType.CONNECT, null, true, CSRF_TOKEN))).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "wrong-csrf-token")
    void rejectsConnectWithoutTheMatchingCsrfToken(String csrfHeader) {
        assertDenied(message(SimpMessageType.CONNECT, null, true, csrfHeader));
    }

    @Test
    void rejectsUnauthenticatedConnectEvenWithTheCorrectCsrfToken() {
        assertDenied(message(SimpMessageType.CONNECT, null, false, CSRF_TOKEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user/queue/ai-tasks",
            "/user/queue/command-results"
    })
    void acceptsTheTwoPersonalSubscriptions(String destination) {
        assertThat(send(message(SimpMessageType.SUBSCRIBE, destination, true, null))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user/7/queue/ai-tasks",
            "/topic/trips/14/events",
            "/queue/ai-tasks",
            "/user/queue/*"
    })
    void rejectsOtherSubscriptions(String destination) {
        assertDenied(message(SimpMessageType.SUBSCRIBE, destination, true, null));
    }

    @Test
    void rejectsAnUnauthenticatedPersonalSubscription() {
        assertDenied(message(SimpMessageType.SUBSCRIBE, "/user/queue/ai-tasks", false, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/app/trips/14/update",
            "/app/trips/14/attachments/delete",
            "/topic/trips/14/events",
            "/user/queue/ai-tasks"
    })
    void rejectsClientSendMessages(String destination) {
        assertDenied(message(SimpMessageType.MESSAGE, destination, true, null));
    }

    @ParameterizedTest
    @EnumSource(value = SimpMessageType.class, names = {
            "UNSUBSCRIBE", "DISCONNECT", "HEARTBEAT"
    })
    void acceptsAuthenticatedConnectionManagementMessages(SimpMessageType type) {
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
        headers.setSessionAttributes(Map.of(
                CsrfToken.class.getName(),
                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", CSRF_TOKEN)
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
