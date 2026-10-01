package com.yeodam.yeodambe.integration.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.integration.client.AiEc2Starter;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TripPhotoAnalysisHttpContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        server.stop(0);
    }

    @Test
    void 준비된_AI서버에_HTTP_1_1로_Bearer인증과_사진분석_요청을_전송한다() throws Exception {
        AtomicReference<String> healthMethod = new AtomicReference<>();
        AtomicReference<String> healthPath = new AtomicReference<>();
        AtomicReference<String> healthAuthorization = new AtomicReference<>();
        AtomicReference<String> healthUpgrade = new AtomicReference<>();
        AtomicReference<String> healthHttp2Settings = new AtomicReference<>();
        AtomicReference<String> healthRequestId = new AtomicReference<>();
        AtomicReference<String> analysisMethod = new AtomicReference<>();
        AtomicReference<String> analysisPath = new AtomicReference<>();
        AtomicReference<String> analysisAuthorization = new AtomicReference<>();
        AtomicReference<String> analysisUpgrade = new AtomicReference<>();
        AtomicReference<String> analysisHttp2Settings = new AtomicReference<>();
        AtomicReference<String> analysisRequestId = new AtomicReference<>();
        AtomicReference<JsonNode> analysisBody = new AtomicReference<>();

        server.createContext("/health", exchange -> {
            healthMethod.set(exchange.getRequestMethod());
            healthPath.set(exchange.getRequestURI().getPath());
            healthAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            healthUpgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            healthHttp2Settings.set(exchange.getRequestHeaders().getFirst("HTTP2-Settings"));
            healthRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            respond(exchange, "{\"status\":\"ok\",\"model_loaded\":true}");
        });
        server.createContext("/trips/7/process", exchange -> {
            analysisMethod.set(exchange.getRequestMethod());
            analysisPath.set(exchange.getRequestURI().getPath());
            analysisAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            analysisUpgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            analysisHttp2Settings.set(exchange.getRequestHeaders().getFirst("HTTP2-Settings"));
            analysisRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            analysisBody.set(json.readTree(exchange.getRequestBody()));
            respond(exchange, """
                    {"trip_id":7,"execution_id":"run-1","status":"COMPLETED",
                     "result":{"places":[],"unclassified":[],"failed":[]}}
                    """);
        });

        TripPhotoAnalysisRequest request = request();
        AiEc2Starter starter = mock(AiEc2Starter.class);
        TripPhotoAnalysisService service = new TripPhotoAnalysisService(
                RestClient.builder(), starter, baseUrl(), "test-api-key",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        MDC.put("request_id", "request-123");

        JsonNode result = service.analyze(7L, "run-1", request, () -> true);

        verify(starter).ensureRunning();
        assertEquals("GET", healthMethod.get());
        assertEquals("/health", healthPath.get());
        assertEquals("Bearer test-api-key", healthAuthorization.get());
        assertNull(healthUpgrade.get());
        assertNull(healthHttp2Settings.get());
        assertEquals("request-123", healthRequestId.get());
        assertEquals("POST", analysisMethod.get());
        assertEquals("/trips/7/process", analysisPath.get());
        assertEquals("Bearer test-api-key", analysisAuthorization.get());
        assertNull(analysisUpgrade.get());
        assertNull(analysisHttp2Settings.get());
        assertEquals("request-123", analysisRequestId.get());
        assertEquals(json.readTree(json.writeValueAsString(request)), analysisBody.get());
        JsonNode camera = analysisBody.get().path("attachments").get(1);
        assertEquals("2010-01-01T12:44:33+09:00", camera.path("taken_at").asString());
        assertEquals("SAMSUNG NX100", camera.path("device_model").asString());
        assertTrue(camera.path("latitude").isNull());
        assertTrue(camera.path("longitude").isNull());
        assertTrue(result.path("places").isArray());
    }

    @Test
    void AI_상태조회와_취소에도_요청_ID를_전달한다() {
        AtomicReference<String> statusRequestId = new AtomicReference<>();
        AtomicReference<String> cancelRequestId = new AtomicReference<>();

        server.createContext("/trips/7/process", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                statusRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
                respond(exchange, """
                        {"trip_id":7,"status":"QUEUED","progress":null,
                         "current_step":null,"result":null,"error":null}
                        """);
                return;
            }

            cancelRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            respond(exchange, "{" + "\"trip_id\":7,\"status\":\"CANCELED\"}");
        });

        TripPhotoAnalysisService service = new TripPhotoAnalysisService(
                RestClient.builder(), mock(AiEc2Starter.class), baseUrl(), "test-api-key",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        MDC.put("request_id", "request-456");

        service.findStatus(7L);
        service.cancel(7L);

        assertEquals("request-456", statusRequestId.get());
        assertEquals("request-456", cancelRequestId.get());
    }

    private TripPhotoAnalysisRequest request() {
        return new TripPhotoAnalysisRequest(
                "run-1",
                "여행",
                new TripPhotoAnalysisRequest.Period(
                        LocalDate.of(2026, 9, 20),
                        LocalDate.of(2026, 9, 21)),
                List.of(),
                List.of(new TripPhotoAnalysisRequest.Photo(
                        1L, "analyze", null, null, null, null),
                        new TripPhotoAnalysisRequest.Photo(2L, "camera-analyze",
                                OffsetDateTime.parse("2010-01-01T12:44:33+09:00"),
                                null, null, "SAMSUNG NX100")));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
