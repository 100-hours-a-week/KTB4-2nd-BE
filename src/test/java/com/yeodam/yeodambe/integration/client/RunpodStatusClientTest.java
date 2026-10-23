package com.yeodam.yeodambe.integration.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.common.exception.RunpodStatusUnavailableException;
import com.yeodam.yeodambe.integration.service.RunpodStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunpodStatusClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private RunpodStatusService service;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RunpodStatusClient.class);
    private ListAppender<ILoggingEvent> logs;
    private int status = 200;
    private String response = "{\"running\":true,\"ready\":false}";
    private long delayMillis;

    @BeforeEach
    void setUp() throws Exception {
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/runpod/status", exchange -> {
            method.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
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
        RunpodStatusClient client = new RunpodStatusClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret-key",
                Duration.ofSeconds(1), Duration.ofMillis(300));
        service = new RunpodStatusService(client, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.clear();
        server.stop(0);
        executor.shutdownNow();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void 실행과_준비_상태를_그대로_반환하고_인증과_요청_ID를_전달한다(boolean running, boolean ready) {
        response = "{\"running\":" + running + ",\"ready\":" + ready + "}";
        MDC.put("request_id", "runpod-request-1");

        var result = service.getStatus();
        assertThat(result.running()).isEqualTo(running);
        assertThat(result.ready()).isEqualTo(ready);
        assertThat(method.get()).isEqualTo("GET /runpod/status");
        assertThat(authorization.get()).isEqualTo("Bearer secret-key");
        assertThat(requestId.get()).isEqualTo("runpod-request-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"ready\":false}", "{\"running\":true}",
            "{\"running\":true,\"ready\":null}", "{\"running\":true,\"ready\":\"false\"}",
            "{\"running\":true,\"ready\":0}", "{\"running\":\"false\",\"ready\":false}",
            "{\"running\":0,\"ready\":false}", "{\"running\":[],\"ready\":false}", "invalid secret-key"})
    void 잘못된_응답을_꺼짐으로_처리하지_않는다(String invalid) {
        response = invalid;

        assertThatThrownBy(service::getStatus)
                .isInstanceOf(RunpodStatusUnavailableException.class)
                .hasMessage(null)
                .hasCause(null);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404, 500, 503, 504})
    void AI_HTTP_오류를_민감정보_없이_변환한다(int code) {
        status = code;
        response = "secret-key private-response";

        assertThatThrownBy(service::getStatus)
                .isInstanceOf(RunpodStatusUnavailableException.class)
                .hasMessage(null)
                .hasCause(null);
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("secret-key", "private-response");
            assertThat(event.getKeyValuePairs().toString()).contains("runpod_status", "duration_ms")
                    .doesNotContain("secret-key", "private-response");
        }
    }

    @Test
    void 응답_시간_초과를_꺼짐으로_처리하지_않는다() {
        delayMillis = 1000;

        assertThatThrownBy(service::getStatus)
                .isInstanceOf(RunpodStatusUnavailableException.class)
                .hasCause(null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void Pod_상태를_알_수_없으면_null을_정상_반환한다(boolean ready) {
        response = "{\"running\":null,\"ready\":" + ready + "}";

        var result = service.getStatus();
        assertThat(result.running()).isNull();
        assertThat(result.ready()).isEqualTo(ready);
    }

    @Test
    void 연결_실패를_꺼짐으로_처리하지_않는다() {
        server.stop(0);

        assertThatThrownBy(service::getStatus)
                .isInstanceOf(RunpodStatusUnavailableException.class)
                .hasCause(null);
    }
}
