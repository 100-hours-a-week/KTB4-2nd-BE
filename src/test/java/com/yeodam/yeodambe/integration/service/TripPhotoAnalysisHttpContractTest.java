package com.yeodam.yeodambe.integration.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.integration.client.AiEc2Starter;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        server.stop(0);
    }

    @Test
    void 준비된_AI서버에_Bearer인증과_사진분석_요청을_전송한다() throws Exception {
        AtomicReference<String> healthMethod = new AtomicReference<>();
        AtomicReference<String> healthPath = new AtomicReference<>();
        AtomicReference<String> healthAuthorization = new AtomicReference<>();
        AtomicReference<String> analysisMethod = new AtomicReference<>();
        AtomicReference<String> analysisPath = new AtomicReference<>();
        AtomicReference<String> analysisAuthorization = new AtomicReference<>();
        AtomicReference<JsonNode> analysisBody = new AtomicReference<>();

        server.createContext("/health", exchange -> {
            healthMethod.set(exchange.getRequestMethod());
            healthPath.set(exchange.getRequestURI().getPath());
            healthAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "{\"status\":\"ok\",\"model_loaded\":true}");
        });
        server.createContext("/trips/7/process", exchange -> {
            analysisMethod.set(exchange.getRequestMethod());
            analysisPath.set(exchange.getRequestURI().getPath());
            analysisAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
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

        JsonNode result = service.analyze(7L, "run-1", request, () -> true);

        verify(starter).ensureRunning();
        assertEquals("GET", healthMethod.get());
        assertEquals("/health", healthPath.get());
        assertEquals("Bearer test-api-key", healthAuthorization.get());
        assertEquals("POST", analysisMethod.get());
        assertEquals("/trips/7/process", analysisPath.get());
        assertEquals("Bearer test-api-key", analysisAuthorization.get());
        assertEquals(json.readTree(json.writeValueAsString(request)), analysisBody.get());
        assertTrue(result.path("places").isArray());
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
                        1L, "analyze", null, null, null, null)));
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
