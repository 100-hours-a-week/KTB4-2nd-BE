package com.yeodam.yeodambe.integration.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
import com.yeodam.yeodambe.integration.service.request.AiQueryParseRequest;
import com.yeodam.yeodambe.integration.service.request.AiQuerySearchRequest;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse.Intent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class AiQueryClientTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(AiQueryClient.class);
    private ListAppender<ILoggingEvent> logs;
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
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
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
        logs.stop();
        MDC.clear();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void HTTP_1_1로_스네이크_케이스_요청과_인증_토큰과_요청_ID를_전송한다() {
        response = """
                {"intent":"QUESTION","date_from":"2024-02-29","date_to":null,
                 "region_names":["제주특별자치도"],"visual_query":"바다"}
                """;
        MDC.put("request_id", "search-request-1");
        assertThat(client.parse(new AiQueryParseRequest("제주 바다", null)).dateFrom())
                .hasToString("2024-02-29");
        assertThat(method.get()).isEqualTo("POST /query/parse");
        assertThat(authorization.get()).isEqualTo("Bearer secret-key");
        assertThat(requestId.get()).isEqualTo("search-request-1");
        assertThat(protocol.get()).isEqualTo("HTTP/1.1");
        var json = new ObjectMapper().readTree(body.get());
        assertThat(json.path("query").asString()).isEqualTo("제주 바다");
        assertThat(json.has("trip_id")).isTrue();
        assertThat(json.path("trip_id").isNull()).isTrue();
        response = """
                {"attachments":[{"trip_attachment_id":7,"score":0.8}],
                 "answer":null,"answer_error":"ANSWER_FAILED"}
                """;
        var result = client.search(new AiQuerySearchRequest(Intent.QUESTION, "바다", List.of(7L), 30));
        assertThat(result.attachments().getFirst().tripAttachmentId()).isEqualTo(7L);
        assertThat(result.answerError()).isEqualTo("ANSWER_FAILED");
        assertThat(method.get()).isEqualTo("POST /query/search");
        json = new ObjectMapper().readTree(body.get());
        assertThat(json.path("candidate_attachment_ids").toString()).isEqualTo("[7]");
        assertThat(json.path("limit").asInt()).isEqualTo(30);
        assertThat(json.path("visual_query").asString()).isEqualTo("바다");
    }

    @Test
    void HTTP_실패와_잘못된_응답_계약을_내부_정보_노출_없이_거부한다() {
        for (int code : List.of(400, 500)) {
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
    void 읽기_시간_초과와_연결_거부를_검색_서비스_예외로_변환한다() {
        delayMillis = 500;
        assertThatThrownBy(() -> client.parse(new AiQueryParseRequest("바다 사진", null)))
                .isInstanceOf(AiQueryUnavailableException.class).hasCause(null);
        server.stop(0);
        assertThatThrownBy(() -> client.parse(new AiQueryParseRequest("바다 사진", null)))
                .isInstanceOf(AiQueryUnavailableException.class).hasCause(null);
    }
}
