package com.yeodam.yeodambe.story.client;

import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewFailedException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewInProgressException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewTimeoutException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewUnavailableException;
import com.yeodam.yeodambe.story.client.request.StoryTonePreviewAiRequest;
import com.yeodam.yeodambe.story.entity.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class StoryTonePreviewAiClientTest {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private int status = 200;
    private long delayMillis;
    private String response = """
            {"story_summary":"새 요약","blocks":[{"order_number":1,"trip_attachment_id":101,
            "day_label":"첫째 날","detail_summary":"새 캡션","memo":null}]}
            """;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/trips", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        MDC.clear();
        server.stop(0);
    }

    @Test
    void 계약주소와_JSON과_인증과_요청ID를_전달하고_응답을_읽는다() {
        MDC.put("request_id", "preview-test-request");
        var result = client(Duration.ofSeconds(2)).preview(7L, request());
        assertThat(path.get()).isEqualTo("POST /trips/7/story/rewrite");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestId.get()).isEqualTo("preview-test-request");
        assertThat(contentType.get()).startsWith("application/json");
        var mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(body.get())).isEqualTo(mapper.readTree("""
                {"trip_name":"여행","period":{"start_date":"2026-10-01","end_date":"2026-10-03"},
                "mood":"PLAIN","blocks":[{"order_number":1,"trip_attachment_id":101,
                "place_name":"해변","taken_at":"2026-10-01T12:00:00+09:00",
                "analyze_storage_key":"analyze/101.jpg","day_label":"첫째 날",
                "detail_summary":"기존 캡션","memo":null}]}
                """));
        assertThat(result.storySummary()).isEqualTo("새 요약");
        assertThat(result.blocks().getFirst().tripAttachmentId()).isEqualTo(101L);
        assertThat(result.blocks().getFirst().detailSummary()).isEqualTo("새 캡션");
    }

    @Test
    void 요청ID가_없으면_헤더를_생략한다() {
        client(Duration.ofSeconds(2)).preview(7L, request());
        assertThat(requestId.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 503, 504})
    void AI_HTTP상태를_전용예외로_바꾸고_재시도하지않는다(int httpStatus) {
        status = httpStatus;
        Class<? extends RuntimeException> expected = switch (httpStatus) {
            case 409 -> StoryTonePreviewInProgressException.class;
            case 503 -> StoryTonePreviewUnavailableException.class;
            default -> StoryTonePreviewTimeoutException.class;
        };
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).preview(7L, request()))
                .isInstanceOf(expected);
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 422, 500})
    void AI인증과_계약과_내부오류를_FE입력오류로_노출하지않는다(int httpStatus) {
        status = httpStatus;
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).preview(7L, request()))
                .isInstanceOf(StoryTonePreviewFailedException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 빈본문과_잘못된JSON을_거절한다() {
        for (String invalid : List.of("", "not-json")) {
            response = invalid;
            assertThatThrownBy(() -> client(Duration.ofSeconds(2)).preview(7L, request()))
                    .isInstanceOf(StoryTonePreviewFailedException.class);
        }
    }

    @Test
    void 응답대기시간초과를_시간초과예외로_변환한다() {
        delayMillis = 500;
        assertThatThrownBy(() -> client(Duration.ofMillis(100)).preview(7L, request()))
                .isInstanceOf(StoryTonePreviewTimeoutException.class);
    }

    @Test
    void 연결거절을_이용불가예외로_변환한다() {
        var client = client(Duration.ofSeconds(2));
        server.stop(0);
        assertThatThrownBy(() -> client.preview(7L, request()))
                .isInstanceOf(StoryTonePreviewUnavailableException.class);
    }

    private StoryTonePreviewAiClient client(Duration readTimeout) {
        return new StoryTonePreviewAiClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                Duration.ofSeconds(1), readTimeout);
    }

    private StoryTonePreviewAiRequest request() {
        return new StoryTonePreviewAiRequest("여행", new StoryTonePreviewAiRequest.Period(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)), Story.Mood.PLAIN,
                List.of(new StoryTonePreviewAiRequest.Block(1, 101L, "해변",
                        OffsetDateTime.parse("2026-10-01T12:00:00+09:00"), "analyze/101.jpg",
                        "첫째 날", "기존 캡션", null)));
    }
}
