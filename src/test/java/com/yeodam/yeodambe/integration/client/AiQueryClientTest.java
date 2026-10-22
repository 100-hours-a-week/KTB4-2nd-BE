package com.yeodam.yeodambe.integration.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
import com.yeodam.yeodambe.common.exception.AiQueryTimeoutException;
import com.yeodam.yeodambe.integration.service.request.AiQueryParseRequest;
import com.yeodam.yeodambe.integration.service.request.AiQuerySearchRequest;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse.Intent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class AiQueryClientTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(AiQueryClient.class);
    private ListAppender<ILoggingEvent> logs;
    private final Logger workflowLogger = (Logger) LoggerFactory.getLogger(WorkflowLog.class);
    private HttpServer server;
    private ExecutorService executor;
    private AiQueryClient client;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> protocol = new AtomicReference<>();
    private int status = 200;
    private String response = "{}";
    private long delayMillis;

    @BeforeEach
    void setUp() throws Exception {
        logs = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        logs.start();
        logger.addAppender(logs);
        workflowLogger.addAppender(logs);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/query", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            method.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            protocol.set(exchange.getProtocol());
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new AiQueryClient(RestClient.builder(), baseUrl(), "secret-key",
                Duration.ofSeconds(1), Duration.ofMillis(200));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        workflowLogger.detachAppender(logs);
        logs.stop();
        MDC.clear();
        server.stop(0);
        executor.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "search"})
    void AI_요청_시작과_응답_완료_시간을_같은_요청_ID로_기록한다(String stage) {
        MDC.put("request_id", "search-request-1");
        response = "{}";

        request(stage);

        assertThat(logs.list.stream().anyMatch(event ->
                "ai_query_request_started".equals(field(event, "event")))).isTrue();
        assertAiTiming(stage, "success");
        assertThat(requestId.get()).isEqualTo("search-request-1");
        assertThat(MDC.get("request_id")).isEqualTo("search-request-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "search"})
    void AI_요청_실패_시간도_같은_요청_ID로_기록한다(String stage) {
        MDC.put("request_id", "search-request-1");
        status = 504;
        response = "secret-key private-query";

        assertThatThrownBy(() -> request(stage)).isInstanceOf(AiQueryTimeoutException.class);

        assertAiTiming(stage, "failure");
        assertSafeFailureLogs(stage);
    }

    private void assertAiTiming(String stage, String result) {
        ILoggingEvent event = logs.list.stream()
                .filter(log -> ("ai_query_" + stage).equals(field(log, "event")))
                .findFirst().orElseThrow();
        assertThat(field(event, "result")).isEqualTo(result);
        assertThat(field(event, "workflow")).isEqualTo("natural_language_search");
        assertThat(event.getMDCPropertyMap()).containsEntry("request_id", "search-request-1");
        assertThat((Long) field(event, "duration_ms")).isGreaterThanOrEqualTo(0L);
        Instant start = Instant.parse((String) field(event, "stage_started_at"));
        Instant finish = Instant.parse((String) field(event, "stage_finished_at"));
        assertThat(finish).isAfterOrEqualTo(start);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getKeyValuePairs().toString()).doesNotContain("secret-key", "private-query", baseUrl());
    }

    private Object field(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst().orElse(null);
    }

    @Test
    void HTTP_1_1로_스네이크_케이스_요청과_인증_토큰과_요청_ID를_전송한다() {
        response = """
                {"intent":"QUESTION","date_from":"2024-02-29","date_to":null,
                 "region_names":["제주특별자치도"],"visual_query":"바다","parsed":true}
                """;
        MDC.put("request_id", "search-request-1");
        assertThat(client.parse(new AiQueryParseRequest("제주 바다", LocalDate.of(2026, 10, 12))).dateFrom())
                .hasToString("2024-02-29");
        assertThat(method.get()).isEqualTo("POST /query/parse");
        assertThat(authorization.get()).isEqualTo("Bearer secret-key");
        assertThat(requestId.get()).isEqualTo("search-request-1");
        assertThat(protocol.get()).isEqualTo("HTTP/1.1");
        var json = new ObjectMapper().readTree(body.get());
        assertThat(json.path("query").asString()).isEqualTo("제주 바다");
        assertThat(json.has("trip_id")).isFalse();
        assertThat(json.path("reference_date").asString()).isEqualTo("2026-10-12");
        response = """
                {"attachments":[{"trip_attachment_id":7,"score":0.8}],
                 "answer":null,"answer_error":"ANSWER_FAILED"}
                """;
        var result = client.search(new AiQuerySearchRequest("제주 바다", Intent.QUESTION, "바다",
                List.of(new AiQuerySearchRequest.Candidate(7L, "trips/1/analyze/7.jpg",
                        OffsetDateTime.parse("2026-10-12T00:30:00+09:00"), "해변", "제주 여행"),
                        new AiQuerySearchRequest.Candidate(8L, "trips/1/analyze/8.jpg", null, null, null)), 30));
        assertThat(result.attachments().getFirst().tripAttachmentId()).isEqualTo(7L);
        assertThat(result.answerError()).isEqualTo("ANSWER_FAILED");
        assertThat(method.get()).isEqualTo("POST /query/search");
        json = new ObjectMapper().readTree(body.get());
        assertThat(json.has("candidate_attachment_ids")).isFalse();
        assertThat(json.path("candidates").get(0).path("trip_attachment_id").asLong()).isEqualTo(7L);
        assertThat(json.path("query").asString()).isEqualTo("제주 바다");
        var candidate = json.path("candidates").get(0);
        assertThat(candidate.path("analyze_storage_key").asString()).isEqualTo("trips/1/analyze/7.jpg");
        assertThat(candidate.path("taken_at").asString()).isEqualTo("2026-10-12T00:30:00+09:00");
        assertThat(candidate.path("place_name").asString()).isEqualTo("해변");
        assertThat(candidate.path("trip_name").asString()).isEqualTo("제주 여행");
        assertThat(json.path("candidates").get(1).path("taken_at").isNull()).isTrue();
        assertThat(json.path("limit").asInt()).isEqualTo(30);
        assertThat(json.path("visual_query").asString()).isEqualTo("바다");
    }

    @Test
    void HTTP_실패와_잘못된_응답_계약을_내부_정보_노출_없이_거부한다() {
        for (int code : List.of(400, 422, 500, 503)) {
            status = code;
            response = "secret-key private-query";
            assertThatThrownBy(() -> client.parse(new AiQueryParseRequest("private-query", null)))
                    .isInstanceOf(AiQueryUnavailableException.class)
                    .hasMessage(null).hasCause(null);
        }
        status = 200;
        for (String invalid : List.of("", "invalid secret-key", "{\"intent\":\"UNKNOWN\"}",
                "{\"intent\":\"SEARCH\",\"date_from\":\"2024-02-30\"}")) {
            response = invalid;
            assertThatThrownBy(() -> client.parse(new AiQueryParseRequest("private-query", null)))
                    .isInstanceOf(AiQueryUnavailableException.class).hasCause(null);
        }
    }

    @Test
    void 실패_로그에_처리_단계를_포함하고_민감정보와_외부_예외를_제외한다() {
        status = 500;
        response = "secret-key private-query";
        assertThatThrownBy(() -> client.parse(new AiQueryParseRequest("private-query", null)))
                .isInstanceOf(AiQueryUnavailableException.class);
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("secret-key", "private-query", baseUrl());
            assertThat(event.getKeyValuePairs().toString()).contains("parse")
                    .doesNotContain("secret-key", "private-query", baseUrl());
        }
    }

    @Test
    void 연결_거부는_검색_서비스_불가_예외로_변환한다() {
        server.stop(0);
        assertThatThrownBy(() -> client.parse(new AiQueryParseRequest("바다 사진", null)))
                .isInstanceOf(AiQueryUnavailableException.class).hasCause(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "search"})
    void AI의_504는_민감정보_없이_시간_초과_예외로_구분한다(String stage) {
        status = 504;
        response = "secret-key private-query";
        assertThatThrownBy(() -> request(stage))
                .hasMessage(null)
                .hasCause(null)
                .isExactlyInstanceOf(AiQueryTimeoutException.class);
        assertSafeFailureLogs(stage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "search"})
    void 읽기_시간_초과는_민감정보_없이_시간_초과_예외로_구분한다(String stage) {
        delayMillis = 500;
        assertThatThrownBy(() -> request(stage))
                .hasMessage(null)
                .hasCause(null)
                .isExactlyInstanceOf(AiQueryTimeoutException.class);
        assertSafeFailureLogs(stage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "search"})
    void 모델이나_벡터_DB를_사용할_수_없으면_서비스_불가_예외로_구분한다(String stage) {
        status = 503;
        response = "secret-key private-query";
        assertThatThrownBy(() -> request(stage))
                .isExactlyInstanceOf(AiQueryUnavailableException.class)
                .hasMessage(null)
                .hasCause(null);
        assertSafeFailureLogs(stage);
    }

    private void request(String stage) {
        if ("parse".equals(stage)) {
            client.parse(new AiQueryParseRequest("private-query", null));
        } else {
            client.search(new AiQuerySearchRequest(
                    "private-query", Intent.QUESTION, "바다", List.of(), 30));
        }
    }

    private void assertSafeFailureLogs(String stage) {
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("secret-key", "private-query", baseUrl());
            assertThat(event.getKeyValuePairs().toString()).contains(stage)
                    .doesNotContain("secret-key", "private-query", baseUrl());
        }
    }

}
