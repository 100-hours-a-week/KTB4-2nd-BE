package com.yeodam.websocket.common.websocket.health;

import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.client.response.BackendAuthenticationResponse.AuthenticationData;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "websocket.backend-auth.service-token=readiness-test-service-token")
class WebSocketRedisReadinessTest {

    private static final int REDIS_PORT = availablePort();
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))));
    private static final String CHANNEL = "yeodam:test-readiness-" + UUID.randomUUID() + ":events:v1";

    @DynamicPropertySource
    static void redisSettings(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.ssl.enabled", () -> false);
        registry.add("websocket.events.channel", () -> CHANNEL);
    }

    @LocalServerPort private int port;
    @MockitoBean private BackendAuthenticationClient authenticationClient;
    @Autowired private RedisSubscriptionState state;
    @Autowired private WebSocketSessionManager sessions;
    @Autowired private SimpUserRegistry users;
    @Autowired private StringRedisTemplate redis;
    @Autowired @Qualifier("webSocketEventListenerContainer")
    private RedisMessageListenerContainer listener;
    private final HttpClient http = HttpClient.newHttpClient();

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    void separatesLivenessFromSubscriptionReadinessAndRecoversAfterRedisRestarts() throws Exception {
        awaitReady();
        assertHealth("liveness", 200, "UP");
        assertHealth("readiness", 200, "UP");
        try (Connection existing = connect()) {
            assertDelivery(existing);
            REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(1).exec();
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> !state.isReady());
                assertThat(existing.closed.get(10, TimeUnit.SECONDS)).isEqualTo(1011);
                await().atMost(Duration.ofSeconds(5)).until(() -> sessions.getSessions().isEmpty());
                assertHealth("readiness", 503, "DOWN");
                assertHealth("liveness", 200, "UP");
                assertThatThrownBy(this::connect)
                        .hasCauseInstanceOf(WebSocketHandshakeException.class)
                        .satisfies(error -> assertThat(((WebSocketHandshakeException) error.getCause())
                                .getResponse().statusCode()).isEqualTo(503));
            } finally {
                REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
            }
            awaitReady();
            assertHealth("readiness", 200, "UP");
            try (Connection recovered = connect()) {
                assertDelivery(recovered);
            }
        }
    }

    @Test
    void closesConnectionsAndRejectsAdmissionWhenTheEventSubscriptionStops() throws Exception {
        awaitReady();
        try (Connection connection = connect()) {
            listener.stop();
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> !state.isReady());
                assertThat(connection.closed.get(10, TimeUnit.SECONDS)).isEqualTo(1011);
                assertHealth("readiness", 503, "DOWN");
                assertHealth("liveness", 200, "UP");
            } finally {
                listener.start();
            }
            awaitReady();
            assertHealth("readiness", 200, "UP");
        }
    }

    private void awaitReady() {
        await().atMost(Duration.ofSeconds(15)).until(state::isReady);
    }

    private void assertHealth(String group, int status, String health) throws Exception {
        var response = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/actuator/health/" + group))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEqualTo("{\"status\":\"" + health + "\"}");
    }

    private Connection connect() throws Exception {
        when(authenticationClient.authenticate("readiness-token", "context"))
                .thenReturn(new AuthenticationData(42L, "readiness-login", Instant.now().plusSeconds(300), "csrf"));
        Connection connection = new Connection();
        try {
            connection.socket = http.newWebSocketBuilder().header("Origin", "http://localhost:5173")
                    .header("Cookie", "accessToken=readiness-token; CSRF_CONTEXT=context")
                    .subprotocols("v12.stomp")
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/ws"), connection)
                    .get(5, TimeUnit.SECONDS);
            connection.socket.sendText("CONNECT\naccept-version:1.2\nheart-beat:0,0\nX-CSRF-TOKEN:csrf\n\n\u0000", true).join();
            assertThat(connection.next()).startsWith("CONNECTED");
            String id = UUID.randomUUID().toString();
            connection.socket.sendText("SUBSCRIBE\nid:" + id + "\ndestination:/user/queue/ai-tasks\n\n\u0000", true).join();
            await().atMost(Duration.ofSeconds(5)).until(() -> users.getUser("42") != null
                    && users.getUser("42").getSessions().stream().flatMap(s -> s.getSubscriptions().stream())
                    .anyMatch(subscription -> id.equals(subscription.getId())));
            return connection;
        } catch (Exception | AssertionError error) {
            connection.close();
            throw error;
        }
    }

    private void assertDelivery(Connection connection) throws Exception {
        String marker = UUID.randomUUID().toString();
        redis.convertAndSend(CHANNEL, """
                {"eventId":"%s","type":"AI_TASK","schemaVersion":1,"occurredAt":"%s",
                "target":{"type":"USER","id":42},"payload":{"message":"TEST","data":{"marker":"%s"}}}
                """.formatted(UUID.randomUUID(), Instant.now(), marker));
        assertThat(connection.next()).startsWith("MESSAGE").contains(marker);
    }

    private static class Connection implements WebSocket.Listener, AutoCloseable {
        private WebSocket socket;
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }

        @Override public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { messages.add(partial.toString()); partial.setLength(0); }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        String next() throws Exception {
            String frame = messages.poll(5, TimeUnit.SECONDS);
            assertThat(frame).isNotNull();
            return frame;
        }

        @Override public void close() { if (socket != null) socket.abort(); }
    }
}
