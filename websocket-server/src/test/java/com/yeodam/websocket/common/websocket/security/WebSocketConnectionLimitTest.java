package com.yeodam.websocket.common.websocket.security;

import com.yeodam.websocket.RedisTestSupport;
import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.client.response.BackendAuthenticationResponse.AuthenticationData;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "websocket.backend-auth.service-token=limit-test-service-token",
        "websocket.max-connections=1"
})
class WebSocketConnectionLimitTest extends RedisTestSupport {

    @LocalServerPort private int port;
    @Autowired private RedisSubscriptionState subscriptionState;
    @Autowired private WebSocketSessionManager sessions;
    @MockitoBean private BackendAuthenticationClient authenticationClient;

    @Test
    void rejectsExcessHandshakeWithoutAuthenticationAndAllowsItAfterDisconnect() throws Exception {
        await().atMost(Duration.ofSeconds(5)).until(subscriptionState::isReady);
        when(authenticationClient.authenticate("limit-token", "context"))
                .thenReturn(new AuthenticationData(42L, "limit-login", Instant.now().plusSeconds(300), "csrf"));
        try (HttpClient http = HttpClient.newHttpClient(); Connection first = connect(http)) {
            assertThat(sessions.getSessions()).hasSize(1);
            clearInvocations(authenticationClient);

            assertThatThrownBy(() -> connect(http))
                    .hasCauseInstanceOf(WebSocketHandshakeException.class)
                    .satisfies(error -> assertThat(((WebSocketHandshakeException) error.getCause())
                            .getResponse().statusCode()).isEqualTo(503));

            verifyNoInteractions(authenticationClient);
            assertThat(first.closed.isDone()).isFalse();
            assertThat(sessions.getSessions()).hasSize(1);
            first.socket.sendClose(WebSocket.NORMAL_CLOSURE, "test disconnect").join();
            first.closed.get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(5)).until(() -> sessions.getSessions().isEmpty());

            try (Connection replacement = connect(http)) {
                assertThat(sessions.getSessions()).hasSize(1);
                assertThat(replacement.closed.isDone()).isFalse();
                verify(authenticationClient).authenticate("limit-token", "context");
            }
        }
    }

    private Connection connect(HttpClient http) throws Exception {
        Connection connection = new Connection();
        try {
            connection.socket = http.newWebSocketBuilder()
                    .header("Origin", "http://localhost:5173")
                    .header("Cookie", "accessToken=limit-token; CSRF_CONTEXT=context")
                    .subprotocols("v12.stomp")
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/ws"), connection)
                    .get(5, TimeUnit.SECONDS);
            connection.socket.sendText(
                    "CONNECT\naccept-version:1.2\nheart-beat:0,0\nX-CSRF-TOKEN:csrf\n\n\u0000", true).join();
            assertThat(connection.connected.get(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            return connection;
        } catch (Exception | AssertionError error) {
            connection.close();
            throw error;
        }
    }

    private static class Connection implements WebSocket.Listener, AutoCloseable {
        private WebSocket socket;
        private final CompletableFuture<String> connected = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }

        @Override public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { connected.complete(partial.toString()); partial.setLength(0); }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override public void close() { if (socket != null) socket.abort(); }
    }
}
