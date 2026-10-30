package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.websocket.internal-service-token=e2e-service-token")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "websocket.server.jar", matches = ".+")
class TripEditWebSocketEndToEndTest {
    private static final String CHANNEL = "yeodam:e2e:" + UUID.randomUUID();
    @LocalServerPort private int apiPort;
    @TempDir private Path temporaryDirectory;
    @Autowired private DataRedisConnectionDetails redis;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripShareUserRepository shares;
    @Autowired private AccessTokenIssuer tokens;
    @Autowired private LoginSessionStore sessions;
    @Autowired private CsrfTokenStore csrfTokens;
    @Autowired private WebSocketEventPublisher publisher;

    @DynamicPropertySource
    static void channel(DynamicPropertyRegistry registry) {
        registry.add("websocket.events.channel", () -> CHANNEL);
    }

    @Test
    void 두_WS_서버에서_실제_쿠키_명령과_DB_Outbox_Redis_알림이_연결된다() throws Exception {
        Long owner = users.saveAndFlush(new User(UUID.randomUUID() + "@e2e.test", "소유자")).getUserId();
        Long reader = users.saveAndFlush(new User(UUID.randomUUID() + "@e2e.test", "독자")).getUserId();
        Trip trip = new Trip(owner, "통합여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        trip = trips.saveAndFlush(trip);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        shares.saveAndFlush(new TripShareUser(trip.getId(), owner, reader, TripSharePermission.READ,
                now.plusHours(1), now));
        int firstPort = freePort();
        int secondPort = freePort();
        Process first = startServer(firstPort);
        try {
            Process second = startServer(secondPort);
            try {
                ready(firstPort);
                ready(secondPort);
                try (Connection writer = connect(firstPort, owner);
                     Connection viewer = connect(secondPort, reader)) {
                    writer.send("SUBSCRIBE\nid:personal\ndestination:/user/queue/command-results\n\n");
                    viewer.send("SUBSCRIBE\nid:trip\ndestination:/topic/trips/" + trip.getId() + "/events\n\n");
                    awaitSubscription(writer, WebSocketEventTargetType.USER, owner, WebSocketEventType.COMMAND_RESULT);
                    awaitSubscription(viewer, WebSocketEventTargetType.TRIP, trip.getId(), WebSocketEventType.TRIP_EVENT);
                    String command = "SEND\ndestination:/app/trips/" + trip.getId()
                            + "/update\ncontent-type:application/json\n\n{\"requestId\":\"e2e-save\",\"changes\":{\"tripName\":\"소켓수정\"}}";
                    writer.send(command);
                    assertThat(writer.next()).contains("TRIP_UPDATE_SUCCESS", "SUCCEEDED", "e2e-save");
                    assertThat(viewer.next()).contains("TRIP_UPDATED", "소켓수정", "e2e-save");
                    writer.send(command);
                    assertThat(writer.next()).contains("TRIP_UPDATE_SUCCESS", "e2e-save");
                    Trip saved = trips.findById(trip.getId()).orElseThrow();
                    assertThat(saved.getTripName()).isEqualTo("소켓수정");
                    assertThat(saved.getSequence()).isEqualTo(1);
                }
            } finally {
                stop(second);
            }
        } finally {
            stop(first);
        }
    }

    @Test
    void 지연된_구독_확인_메시지를_명령_결과와_혼동하지_않는다() throws Exception {
        Connection connection = new Connection();
        connection.messages.add("{\"message\":\"SUBSCRIPTION_PROBE\"}");
        connection.messages.add("{\"message\":\"SUBSCRIPTION_PROBE\"}");
        connection.messages.add("TRIP_UPDATE_SUCCESS");

        assertThat(connection.next()).isEqualTo("TRIP_UPDATE_SUCCESS");
    }

    private Process startServer(int port) throws Exception {
        ProcessBuilder process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m", "-jar",
                System.getProperty("websocket.server.jar"));
        process.environment().putAll(Map.of(
                "SERVER_PORT", Integer.toString(port),
                "REDIS_HOST", redis.getStandalone().getHost(),
                "REDIS_PORT", Integer.toString(redis.getStandalone().getPort()),
                "BACKEND_INTERNAL_BASE_URL", "http://127.0.0.1:" + apiPort,
                "WEBSOCKET_INTERNAL_SERVICE_TOKEN", "e2e-service-token",
                "WEBSOCKET_EVENTS_CHANNEL", CHANNEL));
        return process.redirectErrorStream(true).redirectOutput(temporaryDirectory.resolve("ws-" + port + ".log").toFile()).start();
    }

    private void ready(int port) {
        HttpClient http = HttpClient.newHttpClient();
        await().atMost(Duration.ofSeconds(45)).ignoreExceptions().until(() -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(2)).build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        });
    }

    private Connection connect(int port, Long userId) throws Exception {
        String sid = UUID.randomUUID().toString();
        String browserContext = UUID.randomUUID().toString();
        sessions.save(sid, userId, UUID.randomUUID().toString());
        csrfTokens.save(browserContext, "e2e-csrf");
        Connection connection = new Connection();
        connection.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:5173")
                .header("Cookie", "accessToken=" + tokens.issue(userId, sid) + "; CSRF_CONTEXT=" + browserContext)
                .subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/ws"), connection).get(10, TimeUnit.SECONDS);
        connection.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nX-CSRF-TOKEN:e2e-csrf\n\n");
        assertThat(connection.next()).startsWith("CONNECTED");
        return connection;
    }

    private void awaitSubscription(Connection connection, WebSocketEventTargetType target, Long id, WebSocketEventType type) {
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            publisher.publish(new WebSocketEvent(UUID.randomUUID(), type, 1, Instant.now(),
                    new WebSocketEventTarget(target, id), new ApiResponse<>("SUBSCRIPTION_PROBE", null)));
            return connection.messages.poll(200, TimeUnit.MILLISECONDS) != null;
        });
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static class Connection implements WebSocket.Listener, AutoCloseable {
        private WebSocket socket;
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
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

        private void send(String frame) {
            socket.sendText(frame + "\u0000", true).join();
        }

        private String next() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            String frame = messages.poll(10, TimeUnit.SECONDS);
            while (frame != null && frame.contains("\"message\":\"SUBSCRIPTION_PROBE\"")) {
                long remaining = deadline - System.nanoTime();
                frame = remaining > 0 ? messages.poll(remaining, TimeUnit.NANOSECONDS) : null;
            }
            assertThat(frame).isNotNull();
            return frame;
        }

        @Override
        public void close() {
            socket.abort();
        }
    }
}
