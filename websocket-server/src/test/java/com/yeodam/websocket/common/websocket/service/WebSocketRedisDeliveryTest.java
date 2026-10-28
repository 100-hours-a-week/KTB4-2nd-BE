package com.yeodam.websocket.common.websocket.service;

import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.client.response.BackendAuthenticationResponse.AuthenticationData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "websocket.backend-auth.service-token=delivery-test-service-token")
class WebSocketRedisDeliveryTest {
    private static final String CHANNEL = "yeodam:test-" + UUID.randomUUID() + ":websocket:events:v1";
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisSettings(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("websocket.events.channel", () -> CHANNEL);
    }

    @LocalServerPort private int port;
    @MockitoBean private BackendAuthenticationClient authenticationClient;
    @Autowired private StringRedisTemplate redis;
    @Autowired @Qualifier("webSocketEventListenerContainer")
    private RedisMessageListenerContainer listener;
    @Autowired private SimpUserRegistry users;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"AI_TASK,/user/queue/ai-tasks", "COMMAND_RESULT,/user/queue/command-results"})
    void deliversOnlyToTheTargetUsersConnections(String type, String destination) throws Exception {
        await().atMost(Duration.ofSeconds(5)).until(listener::isListening);
        try (Connection first = connect(42L, "first", destination);
             Connection second = connect(42L, "second", destination);
             Connection other = connect(99L, "other", destination)) {
            redis.convertAndSend(CHANNEL, event(type, 42L));

            first.assertPayload();
            second.assertPayload();
            assertThat(other.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
            redis.convertAndSend(CHANNEL, event(type, 99L));
            other.assertPayload();
            assertThat(first.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
            assertThat(second.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void processesAValidEventAfterMalformedJson() throws Exception {
        await().atMost(Duration.ofSeconds(5)).until(listener::isListening);
        try (Connection connection = connect(42L, "recovery", "/user/queue/ai-tasks")) {
            redis.convertAndSend(CHANNEL, "{broken-json");
            redis.convertAndSend(CHANNEL, event("AI_TASK", 42L));
            connection.assertPayload();
        }
    }

    private Connection connect(Long userId, String name, String destination) throws Exception {
        when(authenticationClient.authenticate(name + "-token", "context"))
                .thenReturn(new AuthenticationData(userId, name + "-login", Instant.now().plusSeconds(300), "csrf"));
        Connection connection = new Connection();
        connection.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:5173")
                .header("Cookie", "accessToken=" + name + "-token; CSRF_CONTEXT=context")
                .subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/ws"), connection)
                .get(5, TimeUnit.SECONDS);
        connection.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nX-CSRF-TOKEN:csrf\n\n");
        assertThat(connection.next()).startsWith("CONNECTED");
        String subscriptionId = UUID.randomUUID().toString();
        connection.send("SUBSCRIBE\nid:" + subscriptionId + "\ndestination:" + destination + "\n\n");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(users.getUser(userId.toString())).isNotNull();
            assertThat(users.getUser(userId.toString()).getSessions().stream()
                    .flatMap(session -> session.getSubscriptions().stream())
                    .anyMatch(subscription -> subscriptionId.equals(subscription.getId()))).isTrue();
        });
        return connection;
    }

    private String event(String type, Long userId) {
        return """
                {"eventId":"%s","type":"%s","schemaVersion":1,"occurredAt":"2026-10-28T00:00:00Z",
                "target":{"type":"USER","id":%d},"payload":{"message":"TEST_EVENT","data":{"status":"COMPLETED"}}}
                """.formatted(UUID.randomUUID(), type, userId);
    }

    private class Connection implements WebSocket.Listener, AutoCloseable {
        private WebSocket socket;
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }

        @Override
        public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        void send(String frame) { socket.sendText(frame + "\u0000", true).join(); }

        String next() throws InterruptedException {
            String frame = messages.poll(5, TimeUnit.SECONDS);
            assertThat(frame).isNotNull();
            return frame;
        }

        void assertPayload() throws InterruptedException {
            String frame = next();
            assertThat(frame).startsWith("MESSAGE");
            String body = frame.substring(frame.indexOf("\n\n") + 2).replace("\u0000", "");
            assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree(
                    "{\"message\":\"TEST_EVENT\",\"data\":{\"status\":\"COMPLETED\"}}"));
        }

        @Override public void close() { if (socket != null) socket.abort(); }
    }
}
