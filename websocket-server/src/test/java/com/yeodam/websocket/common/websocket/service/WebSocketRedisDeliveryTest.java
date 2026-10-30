package com.yeodam.websocket.common.websocket.service;

import com.yeodam.websocket.RedisTestSupport;
import com.yeodam.websocket.YeodamWebSocketApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import static org.mockito.Mockito.mock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "websocket.backend-auth.service-token=delivery-test-service-token")
class WebSocketRedisDeliveryTest extends RedisTestSupport {
    private static final String CHANNEL = "yeodam:test-" + UUID.randomUUID() + ":websocket:events:v1";
    @DynamicPropertySource
    static void channelSettings(DynamicPropertyRegistry registry) {
        registry.add("websocket.events.channel", () -> CHANNEL);
    }

    @LocalServerPort private int port;
    @MockitoBean private BackendAuthenticationClient authenticationClient;
    @Autowired private StringRedisTemplate redis;
    @Autowired @Qualifier("webSocketEventListenerContainer")
    private RedisMessageListenerContainer listener;
    @Autowired private SimpUserRegistry users;
    @Autowired private RedisSubscriptionState subscriptionState;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"AI_TASK,/user/queue/ai-tasks", "COMMAND_RESULT,/user/queue/command-results"})
    void 대상_사용자의_연결에만_전달한다(String type, String destination) throws Exception {
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"trip", "addition", "story"})
    void 업무_완료_계약을_필드_누락_없이_해당_사용자에게만_전달한다(String kind) throws Exception {
        await().atMost(Duration.ofSeconds(5)).until(listener::isListening);
        String raw = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../src/test/resources/websocket/" + kind + "-completed.json"));
        try (Connection owner = connect(42L, "contract-" + kind, "/user/queue/ai-tasks");
             Connection other = connect(99L, "other-" + kind, "/user/queue/ai-tasks")) {
            redis.convertAndSend(CHANNEL, raw);
            String frame = owner.next();
            assertThat(frame).startsWith("MESSAGE");
            String body = frame.substring(frame.indexOf("\n\n") + 2).replace("\u0000", "");
            assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree(raw).path("payload"));
            assertThat(other.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void 잘못된_JSON_다음의_정상_이벤트를_처리한다() throws Exception {
        await().atMost(Duration.ofSeconds(5)).until(listener::isListening);
        try (Connection connection = connect(42L, "recovery", "/user/queue/ai-tasks")) {
            redis.convertAndSend(CHANNEL, "{broken-json");
            redis.convertAndSend(CHANNEL, event("AI_TASK", 42L));
            connection.assertPayload();
        }
    }

    @Test
    void 독립된_두_웹소켓_앱_사이에서_이벤트를_전달한다() throws Exception {
        try (var secondApp = new SpringApplicationBuilder(
                YeodamWebSocketApplication.class, SecondAppAuthentication.class
        ).run("--server.port=0", "--spring.data.redis.host=" + redisHost(),
                "--spring.data.redis.port=" + redisPort(), "--spring.data.redis.username=",
                "--spring.data.redis.password=", "--spring.data.redis.ssl.enabled=false",
                "--websocket.events.channel=" + CHANNEL,
                "--websocket.backend-auth.service-token=delivery-test-service-token")) {
            int secondPort = ((WebServerApplicationContext) secondApp).getWebServer().getPort();
            var secondUsers = secondApp.getBean(SimpUserRegistry.class);
            var secondAuth = secondApp.getBean("secondAppAuthenticationClient", BackendAuthenticationClient.class);
            var secondRedis = secondApp.getBean(StringRedisTemplate.class);
            var secondListener = secondApp.getBean("webSocketEventListenerContainer", RedisMessageListenerContainer.class);
            await().atMost(Duration.ofSeconds(5)).until(secondListener::isListening);
            try (Connection first = connect(42L, "first-app", "/user/queue/ai-tasks");
                 Connection second = connect(42L, "second-app", "/user/queue/ai-tasks", secondPort, secondUsers, secondAuth);
                 Connection other = connect(99L, "other-app", "/user/queue/ai-tasks", secondPort, secondUsers, secondAuth)) {
                assertThat(users.getUser("99")).isNull();
                redis.convertAndSend(CHANNEL, event("AI_TASK", 42L));
                first.assertPayload();
                second.assertPayload();
                assertThat(other.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
                listener.stop();
                try {
                    assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1011);
                    assertThat(subscriptionState.isReady()).isFalse();
                    assertThat(secondApp.getBean(RedisSubscriptionState.class).isReady()).isTrue();
                    secondRedis.convertAndSend(CHANNEL, event("AI_TASK", 42L));
                    second.assertPayload();
                    assertThat(other.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
                } finally {
                    listener.start();
                }
                await().atMost(Duration.ofSeconds(5)).until(subscriptionState::isReady);
                try (Connection recovered = connect(42L, "recovered-first-app", "/user/queue/ai-tasks")) {
                    secondRedis.convertAndSend(CHANNEL, event("AI_TASK", 42L));
                    recovered.assertPayload();
                    second.assertPayload();
                    assertThat(other.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
                    var secondSessions = secondApp.getBean(
                            com.yeodam.websocket.common.websocket.session.WebSocketSessionManager.class);
                    secondApp.close();
                    assertThat(second.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1012);
                    assertThat(other.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1012);
                    assertThat(secondSessions.canAcceptConnection()).isFalse();
                    await().atMost(Duration.ofSeconds(5)).until(() -> secondSessions.getSessions().isEmpty());
                    redis.convertAndSend(CHANNEL, event("AI_TASK", 42L));
                    recovered.assertPayload();
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecondAppAuthentication {
        @Bean @Primary
        BackendAuthenticationClient secondAppAuthenticationClient() {
            return mock(BackendAuthenticationClient.class);
        }
    }

    private Connection connect(Long userId, String name, String destination) throws Exception {
        return connect(userId, name, destination, port, users, authenticationClient);
    }

    private Connection connect(Long userId, String name, String destination, int targetPort,
                               SimpUserRegistry registry, BackendAuthenticationClient client) throws Exception {
        when(client.authenticate(name + "-token", "context"))
                .thenReturn(new AuthenticationData(userId, name + "-login", Instant.now().plusSeconds(300), "csrf"));
        Connection connection = new Connection();
        connection.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:5173")
                .header("Cookie", "accessToken=" + name + "-token; CSRF_CONTEXT=context")
                .subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://127.0.0.1:" + targetPort + "/api/ws"), connection)
                .get(5, TimeUnit.SECONDS);
        connection.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nX-CSRF-TOKEN:csrf\n\n");
        assertThat(connection.next()).startsWith("CONNECTED");
        String subscriptionId = UUID.randomUUID().toString();
        connection.send("SUBSCRIBE\nid:" + subscriptionId + "\ndestination:" + destination + "\n\n");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(registry.getUser(userId.toString())).isNotNull();
            assertThat(registry.getUser(userId.toString()).getSessions().stream()
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
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }

        @Override
        public CompletableFuture<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

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
