package com.yeodam.websocket.common.websocket.security;

import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.common.websocket.client.response.BackendAuthenticationResponse.AuthenticationData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "websocket.backend-auth.service-token=connection-test-service-token"
)
class WebSocketConnectionSecurityTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private BackendAuthenticationClient authenticationClient;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private SimpUserRegistry userRegistry;

    @Autowired
    private WebSocketSessionManager sessionManager;

    private WebSocket socket;
    private final Frames frames = new Frames();

    @AfterEach
    void closeConnection() {
        if (socket != null) {
            socket.abort();
        }
    }

    @Test
    void connectsAndReceivesItsPersonalNotification() throws Exception {
        connect("http://localhost:5173");
        send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nX-CSRF-TOKEN:existing-token\n\n");
        assertThat(frames.next()).startsWith("CONNECTED");

        send("SUBSCRIBE\nid:personal\ndestination:/user/queue/ai-tasks\nreceipt:subscribed\n\n");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(userRegistry.getUser("42")).isNotNull();
            assertThat(userRegistry.getUser("42").getSessions().stream()
                    .flatMap(session -> session.getSubscriptions().stream())
                    .anyMatch(subscription -> subscription.getDestination().equals("/user/queue/ai-tasks")))
                    .isTrue();
        });
        messagingTemplate.convertAndSendToUser("42", "/queue/ai-tasks", "test-notification");
        assertThat(frames.next()).contains("test-notification");
        assertThat(sessionManager.getSessions()).hasSize(1);
        var credentials = (WebSocketSessionCredentials) sessionManager.getSessions().getFirst()
                .getAttributes().get(WebSocketSessionCredentials.ATTRIBUTE_NAME);
        assertThat(credentials).isNotNull();
        assertThat(credentials.accessToken()).isEqualTo("test-access");
        assertThat(credentials.browserContext()).isEqualTo("test-context");

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test completed").join();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(sessionManager.getSessions()).isEmpty()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "X-CSRF-TOKEN:wrong-token\n"})
    void rejectsConnectWithoutMatchingCsrf(String header) throws Exception {
        connect("http://localhost:5173");
        send("CONNECT\naccept-version:1.2\nheart-beat:0,0\n" + header + "\n");
        assertThat(frames.next()).startsWith("ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SUBSCRIBE\nid:forbidden\ndestination:/user/7/queue/ai-tasks\n\n",
            "SEND\ndestination:/app/trips/14/update\n\n{}"
    })
    void rejectsForbiddenSubscriptionAndClientSend(String command) throws Exception {
        connect("http://localhost:5173");
        send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nX-CSRF-TOKEN:existing-token\n\n");
        assertThat(frames.next()).startsWith("CONNECTED");
        send(command);
        assertThat(frames.next()).startsWith("ERROR");
    }

    @Test
    void rejectsUnlistedOrigin() {
        assertThatThrownBy(() -> connect("https://untrusted.example"))
                .hasCauseInstanceOf(WebSocketHandshakeException.class)
                .satisfies(error -> assertThat(((WebSocketHandshakeException) error.getCause())
                        .getResponse().statusCode()).isEqualTo(403));
    }

    @Test
    void closesTheRealConnectionWhenItsAccessTokenExpires() throws Exception {
        connect("http://localhost:5173", Instant.now().plusSeconds(5));
        send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nX-CSRF-TOKEN:existing-token\n\n");
        assertThat(frames.next()).startsWith("CONNECTED");

        assertThat(frames.closed.get(10, TimeUnit.SECONDS)).isEqualTo(1008);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(sessionManager.getSessions()).isEmpty()
        );
    }

    private void connect(String origin) {
        connect(origin, Instant.parse("2099-01-01T00:00:00Z"));
    }

    private void connect(String origin, Instant expiresAt) {
        when(authenticationClient.authenticate("test-access", "test-context"))
                .thenReturn(new AuthenticationData(
                        42L, "test-login-session", expiresAt, "existing-token"
                ));
        socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .header("Origin", origin)
                .header("Cookie", "accessToken=test-access; CSRF_CONTEXT=test-context")
                .subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://localhost:" + port + "/api/ws"), frames)
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
    }

    private void send(String frame) {
        socket.sendText(frame + "\u0000", true).join();
    }

    private static class Frames implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        private String next() throws InterruptedException {
            String frame = messages.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("server STOMP frame").isNotNull();
            return frame;
        }
    }
}
