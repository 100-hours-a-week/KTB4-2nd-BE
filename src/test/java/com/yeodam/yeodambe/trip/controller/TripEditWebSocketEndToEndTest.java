package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired private TripShareUserRepository shares;
    @Autowired private AccessTokenIssuer tokens;
    @Autowired private LoginSessionStore sessions;
    @Autowired private CsrfTokenStore csrfTokens;
    @Autowired private WebSocketEventPublisher publisher;
    @Autowired
    private TripAttachmentRepository attachments;
    @Autowired
    private TripDetailPlaceRepository places;
    @Autowired
    private StoredFileRepository files;
    @MockitoBean
    private TripAttachmentStorageClient storage;

    @DynamicPropertySource
    static void channel(DynamicPropertyRegistry registry) {
        registry.add("websocket.events.channel", () -> CHANNEL);
    }

    @Test
    void 두_WS_서버에서_공유복구를_전달하고_READ_복구는_거절한다() throws Exception {
        Long owner = users.saveAndFlush(new User(UUID.randomUUID() + "@e2e.test", "소유자")).getUserId();
        Long reader = users.saveAndFlush(new User(UUID.randomUUID() + "@e2e.test", "독자")).getUserId();
        Long editor = users.saveAndFlush(new User(UUID.randomUUID() + "@e2e.test", "편집자")).getUserId();
        Trip trip = new Trip(owner, "통합여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        trip = trips.saveAndFlush(trip);
        TripAttachment photo = restorablePhoto(owner, trip.getId());
        TripAttachment sharedPhoto = restorablePhoto(owner, trip.getId());
        when(storage.createReadUrl(anyString())).thenAnswer(invocation ->
                "https://test.example/" + invocation.getArgument(0, String.class));
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        shares.saveAndFlush(new TripShareUser(trip.getId(), owner, reader, TripSharePermission.READ,
                now.plusHours(1), now));
        shares.saveAndFlush(new TripShareUser(trip.getId(), owner, editor, TripSharePermission.WRITE,
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
                     Connection sharedWriter = connect(firstPort, editor);
                     Connection viewer = connect(secondPort, reader)) {
                    writer.send("SUBSCRIBE\nid:personal\ndestination:/user/queue/command-results\n\n");
                    writer.send("SUBSCRIBE\nid:trip\ndestination:/topic/trips/" + trip.getId() + "/events\n\n");
                    sharedWriter.send("SUBSCRIBE\nid:personal\ndestination:/user/queue/command-results\n\n");
                    sharedWriter.send("SUBSCRIBE\nid:trip\ndestination:/topic/trips/" + trip.getId() + "/events\n\n");
                    viewer.send("SUBSCRIBE\nid:personal\ndestination:/user/queue/command-results\n\n");
                    viewer.send("SUBSCRIBE\nid:trip\ndestination:/topic/trips/" + trip.getId() + "/events\n\n");
                    awaitSubscription(writer, WebSocketEventTargetType.USER, owner, WebSocketEventType.COMMAND_RESULT);
                    awaitSubscription(writer, WebSocketEventTargetType.TRIP, trip.getId(), WebSocketEventType.TRIP_EVENT);
                    awaitSubscription(sharedWriter, WebSocketEventTargetType.USER, editor, WebSocketEventType.COMMAND_RESULT);
                    awaitSubscription(sharedWriter, WebSocketEventTargetType.TRIP, trip.getId(), WebSocketEventType.TRIP_EVENT);
                    awaitSubscription(viewer, WebSocketEventTargetType.USER, reader, WebSocketEventType.COMMAND_RESULT);
                    awaitSubscription(viewer, WebSocketEventTargetType.TRIP, trip.getId(), WebSocketEventType.TRIP_EVENT);
                    String command = "SEND\ndestination:/app/trips/" + trip.getId()
                            + "/update\ncontent-type:application/json\n\n{\"requestId\":\"e2e-save\",\"changes\":{\"tripName\":\"소켓수정\"}}";
                    writer.send(command);
                    assertThat(List.of(writer.next(), writer.next())).satisfiesExactlyInAnyOrder(
                            frame -> assertThat(frame).contains("TRIP_UPDATE_SUCCESS", "SUCCEEDED", "e2e-save"),
                            frame -> assertThat(frame).contains("TRIP_UPDATED", "소켓수정", "e2e-save"));
                    assertThat(viewer.next()).contains("TRIP_UPDATED", "소켓수정", "e2e-save");
                    assertThat(sharedWriter.next()).contains("TRIP_UPDATED", "소켓수정", "e2e-save");
                    writer.send(command);
                    assertThat(writer.next()).contains("TRIP_UPDATE_SUCCESS", "e2e-save");
                    Trip saved = trips.findById(trip.getId()).orElseThrow();
                    assertThat(saved.getTripName()).isEqualTo("소켓수정");
                    assertThat(saved.getSequence()).isEqualTo(1);

                    restorePhoto(owner, photo);

                    for (Connection connection : List.of(writer, viewer)) {
                        assertThat(connection.next()).contains(
                                "TRIP_PHOTOS_RESTORED", "\"sequence\":2", "\"sourceIssue\":\"BLURRY\"",
                                "\"tripAttachmentId\":" + photo.getId(),
                                "\"unclassifiedAttachmentCount\":1", "https://test.example/"
                        );
                    }
                    assertThat(sharedWriter.next()).contains("TRIP_PHOTOS_RESTORED", "\"sequence\":2",
                            "\"tripAttachmentId\":" + photo.getId());
                    assertThat(attachments.findById(photo.getId()).orElseThrow().getClassificationStatus())
                            .isEqualTo(ClassificationStatus.ACTIVE);
                    assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(2);

                    String restoreCommand = "SEND\ndestination:/app/trips/" + trip.getId()
                            + "/attachments/restore\ncontent-type:application/json\n\n{\"requestId\":\"e2e-shared-restore\",\"items\":[{\"tripAttachmentId\":"
                            + sharedPhoto.getId() + ",\"tripPlaceId\":" + sharedPhoto.getTripPlaceId() + "}]}";
                    sharedWriter.send(restoreCommand);
                    assertThat(List.of(sharedWriter.next(), sharedWriter.next())).satisfiesExactlyInAnyOrder(
                            frame -> assertThat(frame).contains("ATTACHMENT_RESTORE_SUCCESS", "SUCCEEDED", "e2e-shared-restore"),
                            frame -> assertThat(frame).contains("TRIP_PHOTOS_RESTORED", "e2e-shared-restore",
                                    "\"tripAttachmentId\":" + sharedPhoto.getId(),
                                    "\"unclassifiedAttachmentCount\":0"));
                    for (Connection connection : List.of(writer, viewer)) {
                        assertThat(connection.next()).contains("TRIP_PHOTOS_RESTORED", "\"sequence\":3",
                                "\"tripAttachmentId\":" + sharedPhoto.getId());
                    }
                    sharedWriter.send(restoreCommand);
                    assertThat(sharedWriter.nextContaining("ATTACHMENT_RESTORE_SUCCESS"))
                            .contains("e2e-shared-restore",
                            "\"sequence\":3");
                    assertThat(attachments.findById(sharedPhoto.getId()).orElseThrow().getClassificationStatus())
                            .isEqualTo(ClassificationStatus.ACTIVE);
                    assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(3);

                    viewer.send("SEND\ndestination:/app/trips/" + trip.getId()
                            + "/attachments/restore\ncontent-type:application/json\n\n{\"requestId\":\"e2e-read-denied\",\"items\":[{\"tripAttachmentId\":"
                            + sharedPhoto.getId() + ",\"tripPlaceId\":" + sharedPhoto.getTripPlaceId() + "}]}");
                    assertThat(viewer.nextContaining("WRITE_PERMISSION_REQUIRED"))
                            .contains("REJECTED", "e2e-read-denied");
                    assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(3);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?",
                            Integer.class, trip.getId())).isEqualTo(3);
                }
            } finally {
                stop(second);
            }
        } finally {
            stop(first);
        }
    }

    @Test
    void 실제_공유_만료_후_이벤트를_전달하지_않고_STOMP_연결을_종료한다() throws Exception {
        Long owner = users.saveAndFlush(new User(UUID.randomUUID() + "@e2e.test", "소유자")).getUserId();
        Long reader = users.saveAndFlush(new User(UUID.randomUUID() + "@e2e.test", "독자")).getUserId();
        Trip trip = new Trip(owner, "만료여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        Long tripId = trips.saveAndFlush(trip).getId();
        int port = freePort();
        Process server = startServer(port);
        try {
            ready(port);
            Instant expiresAt = Instant.now().plusSeconds(10);
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            shares.saveAndFlush(new TripShareUser(tripId, owner, reader, TripSharePermission.READ,
                    LocalDateTime.ofInstant(expiresAt, ZoneId.of("Asia/Seoul")), now));
            try (Connection viewer = connect(port, reader)) {
                viewer.send("SUBSCRIBE\nid:trip\ndestination:/topic/trips/" + tripId + "/events\n\n");
                awaitSubscription(viewer, WebSocketEventTargetType.TRIP, tripId, WebSocketEventType.TRIP_EVENT);
                await().atMost(Duration.ofSeconds(15)).until(() -> Instant.now().isAfter(expiresAt));
                publisher.publish(new WebSocketEvent(UUID.randomUUID(), WebSocketEventType.TRIP_EVENT, 1, Instant.now(),
                        new WebSocketEventTarget(WebSocketEventTargetType.TRIP, tripId),
                        new ApiResponse<>("EXPIRED_SHOULD_NOT_DELIVER", null)));

                assertThat(viewer.closed.get(5, TimeUnit.SECONDS)).isNotNull();
                assertThat(viewer.messages).noneSatisfy(frame -> assertThat(frame).contains("EXPIRED_SHOULD_NOT_DELIVER"));
            }
        } finally {
            stop(server);
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

    private TripAttachment restorablePhoto(Long ownerId, Long tripId) {
        LocalDateTime now = LocalDateTime.now();
        String key = UUID.randomUUID().toString();
        TripDetailPlace place = places.saveAndFlush(TripDetailPlace.fromAnalysis(
                tripId, 1, "복구 장소", BigDecimal.ZERO, BigDecimal.ZERO, now, now, null
        ));
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(ownerId, key + ".jpg", key, "image/jpeg"));
        TripAttachment photo = TripAttachment.initial(tripId, file.getId(), key, key + "-preview", key);
        photo.unclassify(place.getId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF, now,
                BigDecimal.ZERO, BigDecimal.ZERO, 90);
        return attachments.saveAndFlush(photo);
    }

    private void restorePhoto(Long ownerId, TripAttachment photo) throws Exception {
        String sid = UUID.randomUUID().toString();
        String browserContext = UUID.randomUUID().toString();
        sessions.save(sid, ownerId, UUID.randomUUID().toString());
        csrfTokens.save(browserContext, "e2e-csrf");
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + apiPort + "/api/attachments/" + photo.getId() + "/restore"))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", "accessToken=" + tokens.issue(ownerId, sid) + "; CSRF_CONTEXT=" + browserContext)
                .header("X-CSRF-TOKEN", "e2e-csrf")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"tripPlaceId\":" + photo.getTripPlaceId() + "}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ATTACHMENT_RESTORE_SUCCESS");
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
                    new WebSocketEventTarget(target, id), new ApiResponse<>("SUBSCRIPTION_PROBE", type.name())));
            String frame = connection.messages.poll(200, TimeUnit.MILLISECONDS);
            return frame != null && frame.contains("\"data\":\"" + type.name() + "\"");
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
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
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

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
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

        private String nextContaining(String token) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            long remaining = deadline - System.nanoTime();
            String frame = null;
            while (remaining > 0) {
                frame = messages.poll(remaining, TimeUnit.NANOSECONDS);
                if (frame != null && frame.contains(token)) {
                    return frame;
                }
                remaining = deadline - System.nanoTime();
            }
            assertThat(frame).contains(token);
            return frame;
        }

        @Override
        public void close() {
            socket.abort();
        }
    }
}
