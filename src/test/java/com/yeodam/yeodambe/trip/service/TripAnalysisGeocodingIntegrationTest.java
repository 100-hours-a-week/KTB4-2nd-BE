package com.yeodam.yeodambe.trip.service;

import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.event.handler.TripUpdateResultEventHandler;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripAnalysisGeocodingIntegrationTest {
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final HttpServer SERVER = startServer();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private TripUpdateResultEventHandler listener;
    @Autowired
    private AdditionalAttachmentResultWorker worker;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private TripPlaceNameService names;

    @DynamicPropertySource
    static void configureGeocoding(DynamicPropertyRegistry registry) {
        registry.add("kakao.local.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 추가_업로드_결과의_AI_장소명을_저장하고_없을때만_락_밖에서_역지오코딩한다(boolean hasAiName) {
        int requestsBefore = REQUESTS.get();
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        Long ownerId = fixture.createOwner();
        Long tripId = fixture.createTrip(ownerId);
        Long photoId = fixture.prepareAddition(tripId, ownerId).photoId();
        String executionId = scheduling.startNext(tripId).orElseThrow();
        JsonNode result = json.valueToTree(Map.of("places", List.of(Map.of(
                "place_id", "seoul", "latitude", 37.5, "longitude", 127.0,
                "first_taken_at", "2026-09-01T12:00:00+09:00", "last_taken_at", "2026-09-01T12:00:00+09:00",
                "representative_attachment_id", photoId,
                "attachments", List.of(Map.of("trip_attachment_id", photoId, "region_origin", "INFERRED",
                        "latitude", 37.5, "longitude", 127.0, "evaluation", 80)))),
                "unclassified", List.of(), "failed", List.of()));
        ObjectNode place = (ObjectNode) result.path("places").get(0);
        if (hasAiName) {
            place.put("place_name", "성산일출봉");
        } else {
            place.putNull("place_name");
        }
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId).isLocked()).isFalse();
            return call.callRealMethod();
        }).when(names).resolve(eq(tripId), eq(executionId), any(), any());
        listener.consume(json.writeValueAsString(new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.COMPLETED, result, null)));
        worker.applyPendingResults();
        assertThat(REQUESTS.get() - requestsBefore).isEqualTo(hasAiName ? 0 : 1);
        assertThat(jdbc.queryForObject("SELECT status FROM trip_analysis_executions WHERE execution_id = ?",
                String.class, executionId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT place_name FROM trip_detail_places WHERE trip_id = ? AND deleted_at IS NULL",
                String.class, tripId)).isEqualTo(hasAiName ? "성산일출봉" : "테스트박물관");
        assertThat(jdbc.queryForObject("SELECT trip_place_id IS NOT NULL FROM trip_attachments WHERE trip_attachment_id = ?",
                Boolean.class, photoId)).isTrue();
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2/local/geo/coord2address.json", exchange -> {
                REQUESTS.incrementAndGet();
                byte[] body = """
                        {"documents":[{"road_address":{"building_name":"테스트박물관"},
                        "address":{"region_2depth_name":"종로구"}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (exchange) {
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
