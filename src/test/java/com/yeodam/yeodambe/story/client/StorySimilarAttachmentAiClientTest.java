package com.yeodam.yeodambe.story.client;

import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.story.client.request.StorySimilarAttachmentAiRequest;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentAiRequestException;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentInternalErrorMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class StorySimilarAttachmentAiClientTest {
    private HttpServer server;
    private StorySimilarAttachmentAiClient client;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final StorySimilarAttachmentAiRequest request =
            new StorySimilarAttachmentAiRequest(501L, List.of(506L, 507L), 10);
    private int status = 200;
    private long delayMillis;
    private String response = """
            {"source_attachment_id":501,"items":[
              {"trip_attachment_id":507,"score":0.94},
              {"trip_attachment_id":506,"score":0.91}]}
            """;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/trips", exchange -> {
            try (exchange) {
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
        client = client(Duration.ofSeconds(2));
    }

    private StorySimilarAttachmentAiClient client(Duration readTimeout) {
        return new StorySimilarAttachmentAiClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-api-key",
                Duration.ofSeconds(1), readTimeout);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        server.stop(0);
    }

    @Test
    void AI_주소에_계약_JSON과_인증키와_추적_ID를_보내고_결과_순서를_유지한다() {
        MDC.put("request_id", "similar-request-1");

        var actual = client.findSimilar(31L, request);

        assertThat(path.get()).isEqualTo("POST /trips/31/attachments/similar");
        assertThat(authorization.get()).isEqualTo("Bearer test-api-key");
        assertThat(requestId.get()).isEqualTo("similar-request-1");
        assertThat(contentType.get()).startsWith("application/json");
        var mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(body.get())).isEqualTo(mapper.readTree("""
                {"trip_attachment_id":501,"candidate_attachment_ids":[506,507],"limit":10}
                """));
        assertThat(actual.sourceAttachmentId()).isEqualTo(501L);
        assertThat(actual.items()).extracting(item -> item.tripAttachmentId()).containsExactly(507L, 506L);
        assertThat(actual.items().getFirst().score()).isEqualTo(0.94);
    }

    @Test
    void 후보가_없는_정상_응답을_허용하고_추적_ID가_없으면_헤더를_생략한다() {
        response = """
                {"source_attachment_id":501,"items":[]}
                """;

        assertThat(client.findSimilar(31L, request).items()).isEmpty();
        assertThat(requestId.get()).isNull();
    }

    @Test
    void 빈_응답은_유효하지_않은_AI_응답으로_처리한다() {
        response = "";

        var failure = catchThrowableOfType(StorySimilarAttachmentAiRequestException.class,
                () -> client.findSimilar(31L, request));

        assertThat(failure).hasMessage(StorySimilarAttachmentInternalErrorMessage.AI_RESPONSE_INVALID.message());
    }

    @Test
    void HTTP_오류의_본문과_인증키를_상위_예외로_노출하지_않는다() {
        status = 500;
        response = "private-details test-api-key";

        var failure = catchThrowableOfType(StorySimilarAttachmentAiRequestException.class,
                () -> client.findSimilar(31L, request));

        assertThat(failure).hasMessage(StorySimilarAttachmentInternalErrorMessage.AI_REQUEST_FAILED.message())
                .hasCause(null).hasMessageNotContaining("private-details").hasMessageNotContaining("test-api-key");
    }

    @Test
    void 잘못된_JSON을_요청_실패_예외로_전환한다() {
        response = "not-json";

        var failure = catchThrowableOfType(StorySimilarAttachmentAiRequestException.class,
                () -> client.findSimilar(31L, request));

        assertThat(failure).hasMessage(StorySimilarAttachmentInternalErrorMessage.AI_REQUEST_FAILED.message());
    }

    @Test
    void 응답_시간_초과와_연결_실패를_요청_실패_예외로_전환한다() {
        client = client(Duration.ofMillis(100));
        delayMillis = 500;

        var timeout = catchThrowableOfType(StorySimilarAttachmentAiRequestException.class,
                () -> client.findSimilar(31L, request));
        assertThat(timeout).hasMessage(StorySimilarAttachmentInternalErrorMessage.AI_REQUEST_FAILED.message());

        server.stop(0);
        var refused = catchThrowableOfType(StorySimilarAttachmentAiRequestException.class,
                () -> client.findSimilar(31L, request));
        assertThat(refused).hasMessage(StorySimilarAttachmentInternalErrorMessage.AI_REQUEST_FAILED.message());
    }
}
