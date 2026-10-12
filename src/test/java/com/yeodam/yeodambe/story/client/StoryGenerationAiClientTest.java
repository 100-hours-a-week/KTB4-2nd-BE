package com.yeodam.yeodambe.story.client;

import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationAiRequestException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class StoryGenerationAiClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private StoryGenerationAiClient client;
    private final AtomicReference<String> methodAndPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private int status = 200;
    private String response = """
            {"trip_id":241,"execution_id":"execution-1","status":"PROCESSING",
             "progress":{"done":2,"total":4},"current_step":"WRITING"}
            """;
    private long delayMillis;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/trips", exchange -> {
            try (exchange) {
                methodAndPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
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
        client = new StoryGenerationAiClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-api-key",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void getsStatusWithAuthenticationAndRequestId() {
        MDC.put("request_id", "request-241");

        var result = client.getStatus(241L);

        assertThat(methodAndPath.get()).isEqualTo("GET /trips/241/story");
        assertThat(authorization.get()).isEqualTo("Bearer test-api-key");
        assertThat(requestId.get()).isEqualTo("request-241");
        assertThat(result.tripId()).isEqualTo(241L);
        assertThat(result.executionId()).isEqualTo("execution-1");
        assertThat(result.status()).isEqualTo(StoryGenerationAiResponse.Status.PROCESSING);
        assertThat(result.progress().done()).isEqualTo(2);
        assertThat(result.progress().total()).isEqualTo(4);
        assertThat(result.currentStep()).isEqualTo("WRITING");
    }

    @Test
    void sendsDeleteAndReadsCancellationResponseWithoutRequestId() {
        response = """
                {"trip_id":241,"execution_id":"execution-1","status":"CANCELED"}
                """;

        var result = client.cancel(241L);

        assertThat(methodAndPath.get()).isEqualTo("DELETE /trips/241/story");
        assertThat(authorization.get()).isEqualTo("Bearer test-api-key");
        assertThat(requestId.get()).isNull();
        assertThat(result.status()).isEqualTo(StoryGenerationAiResponse.Status.CANCELED);
    }

    @Test
    void readsCompletedBlocksAndIgnoresVerification() {
        response = """
                {"trip_id":241,"execution_id":"execution-1","status":"COMPLETED",
                 "progress":{"done":4,"total":4},"result":{
                 "story_summary":"제주 여행","verification":{"passed":false},
                 "blocks":[{"order_number":1,"trip_place_id":11,"trip_attachment_id":21,
                 "day_label":"첫날","detail_summary":"바다","memo":"산책했다."}]}}
                """;

        var result = client.getStatus(241L);

        assertThat(result.status()).isEqualTo(StoryGenerationAiResponse.Status.COMPLETED);
        assertThat(result.result().storySummary()).isEqualTo("제주 여행");
        assertThat(result.result().blocks()).hasSize(1);
        assertThat(result.result().blocks().getFirst().tripAttachmentId()).isEqualTo(21L);
    }

    @Test
    void preservesTaskNotFoundWithoutTurningItIntoFailedJob() {
        status = 404;
        response = """
                {"error":{"code":"TASK_NOT_FOUND","message":"not registered","detail":null}}
                """;

        var failure = catchThrowableOfType(StoryGenerationAiRequestException.class,
                () -> client.getStatus(241L));

        assertThat(failure).hasMessage(StoryGenerationInternalErrorMessage.AI_STATUS_LOOKUP_FAILED.message());
        assertThat(failure.getStatusCode()).isEqualTo(404);
        assertThat(failure.getErrorCode()).isEqualTo("TASK_NOT_FOUND");
    }

    @Test
    void preservesTaskNotCancelable() {
        status = 409;
        response = """
                {"error":{"code":"TASK_NOT_CANCELABLE","message":"already completed"}}
                """;

        var failure = catchThrowableOfType(StoryGenerationAiRequestException.class,
                () -> client.cancel(241L));

        assertThat(failure).hasMessage(StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED.message());
        assertThat(failure.getStatusCode()).isEqualTo(409);
        assertThat(failure.getErrorCode()).isEqualTo("TASK_NOT_CANCELABLE");
    }

    @Test
    void preservesHttpStatusWhenErrorBodyIsUnreadableWithoutExposingBody() {
        status = 500;
        response = "private-details test-api-key";

        var failure = catchThrowableOfType(StoryGenerationAiRequestException.class,
                () -> client.getStatus(241L));

        assertThat(failure.getStatusCode()).isEqualTo(500);
        assertThat(failure.getErrorCode()).isNull();
        assertThat(failure).hasCause(null)
                .hasMessageNotContaining("private-details")
                .hasMessageNotContaining("test-api-key");
    }

    @Test
    void rejectsEmptySuccessResponse() {
        response = "";

        var failure = catchThrowableOfType(StoryGenerationAiRequestException.class,
                () -> client.getStatus(241L));

        assertThat(failure).hasMessage(StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message());
    }

    @Test
    void convertsMalformedSuccessJsonToInternalException() {
        response = "not-json";

        var failure = catchThrowableOfType(StoryGenerationAiRequestException.class,
                () -> client.getStatus(241L));

        assertThat(failure.getStatusCode()).isNull();
        assertThat(failure.getErrorCode()).isNull();
        assertThat(failure).hasCause(null);
    }

    @Test
    void convertsTimeoutAndConnectionRefusalWithoutHttpStatus() {
        client = new StoryGenerationAiClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-api-key",
                Duration.ofSeconds(1), Duration.ofMillis(100));
        delayMillis = 500;

        var timeout = catchThrowableOfType(StoryGenerationAiRequestException.class,
                () -> client.getStatus(241L));
        assertThat(timeout.getStatusCode()).isNull();
        assertThat(timeout.getErrorCode()).isNull();

        server.stop(0);
        var refused = catchThrowableOfType(StoryGenerationAiRequestException.class,
                () -> client.cancel(241L));
        assertThat(refused.getStatusCode()).isNull();
        assertThat(refused.getErrorCode()).isNull();
        assertThat(refused).hasMessage(StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED.message());
    }
}
