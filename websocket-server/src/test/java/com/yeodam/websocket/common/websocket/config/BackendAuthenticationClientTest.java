package com.yeodam.websocket.common.websocket.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationException;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class BackendAuthenticationClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<HttpClient> httpClients = new ArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private URI baseUrl;
    private BackendAuthenticationClient client;
    private volatile int status;
    private volatile String body;
    private volatile String method;
    private volatile String path;
    private volatile String serviceToken;
    private volatile String cookies;
    private volatile long delayMillis;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        status = 200;
        body = validBody().toString();
        client = createClient(baseUrl, "test-service-token", Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
        httpClients.forEach(HttpClient::close);
    }

    @Test
    void 실제_GET에_정해진_경로와_키와_두_쿠키를_보내고_응답을_읽는다() {
        var data = client.authenticate("test-access-token", "test-browser-context");

        assertThat(data.userId()).isEqualTo(42L);
        assertThat(data.sessionId()).isEqualTo("test-session");
        assertThat(data.csrfToken()).isEqualTo("existing-test-csrf");
        assertThat(data.accessTokenExpiresAt()).isAfter(Instant.now());
        assertThat(method).isEqualTo("GET");
        assertThat(path).isEqualTo("/api/internal/websocket/authentication");
        assertThat(serviceToken).isEqualTo("test-service-token");
        assertThat(cookies).contains("accessToken=test-access-token",
                "CSRF_CONTEXT=test-browser-context").doesNotContain("refreshToken");
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"401,USER_UNAUTHORIZED", "500,BACKEND_UNAVAILABLE",
            "503,BACKEND_UNAVAILABLE", "404,INVALID_RESPONSE", "204,INVALID_RESPONSE"})
    void HTTP_상태를_구분하고_같은_요청을_반복하지_않는다(
            int responseStatus, BackendAuthenticationFailure expected) {
        status = responseStatus;
        body = "";
        assertFailure(client, expected);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"FORBIDDEN,SERVICE_FORBIDDEN", "CSRF_TOKEN_INVALID,CSRF_INVALID",
            "UNKNOWN_ERROR,INVALID_RESPONSE"})
    void 같은_403이라도_응답_코드에_따라_원인을_구분한다(
            String code, BackendAuthenticationFailure expected) {
        status = 403;
        body = "{\"message\":\"" + code + "\",\"data\":null}";
        assertFailure(client, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "{\"message\":\"WRONG\"}",
            "{\"message\":\"WEBSOCKET_AUTHENTICATION_SUCCESS\",\"data\":null}"})
    void 잘못되거나_빈_200_응답은_인증_성공으로_사용하지_않는다(String invalidBody) {
        body = invalidBody;
        assertFailure(client, BackendAuthenticationFailure.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "sessionId", "accessTokenExpiresAt", "csrfToken"})
    void 성공_응답의_필수값이_하나라도_없으면_거절한다(String field) {
        var response = validBody();
        ((ObjectNode) response.get("data")).remove(field);
        body = response.toString();
        assertFailure(client, BackendAuthenticationFailure.INVALID_RESPONSE);
    }

    @Test
    void 응답을_받았더라도_만료_시각이_지났으면_인증을_거절한다() {
        var response = validBody();
        ((ObjectNode) response.get("data"))
                .put("accessTokenExpiresAt", Instant.now().minusSeconds(60).toString());
        body = response.toString();
        assertFailure(client, BackendAuthenticationFailure.USER_UNAUTHORIZED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void 서비스키가_없으면_HTTP_요청을_보내지_않는다(String key) {
        var noKeyClient = createClient(baseUrl, key, Duration.ofSeconds(2));
        assertFailure(noKeyClient, BackendAuthenticationFailure.SERVICE_TOKEN_MISSING);
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void 액세스토큰이_없으면_HTTP_요청을_보내지_않는다(String token) {
        var error = catchThrowableOfType(BackendAuthenticationException.class,
                () -> client.authenticate(token, "test-browser-context"));
        assertThat(error.failure()).isEqualTo(BackendAuthenticationFailure.USER_UNAUTHORIZED);
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void CSRF_CONTEXT가_없으면_HTTP_요청을_보내지_않는다(String context) {
        var error = catchThrowableOfType(BackendAuthenticationException.class,
                () -> client.authenticate("test-access-token", context));
        assertThat(error.failure()).isEqualTo(BackendAuthenticationFailure.CSRF_INVALID);
        assertThat(requests).hasValue(0);
    }

    @Test
    void 응답_지연은_저장소_검증_불가로_분류한다() {
        delayMillis = 1000;
        var shortTimeoutClient = createClient(baseUrl, "test-service-token", Duration.ofMillis(200));
        assertFailure(shortTimeoutClient, BackendAuthenticationFailure.BACKEND_UNAVAILABLE);
        assertThat(requests).hasValue(1);
    }

    @Test
    void 연결이_거절되면_검증_불가로_분류한다() throws IOException {
        var closedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = closedServer.getAddress().getPort();
        closedServer.start();
        closedServer.stop(0);
        var disconnectedClient = createClient(URI.create("http://127.0.0.1:" + port),
                "test-service-token", Duration.ofSeconds(2));
        assertFailure(disconnectedClient, BackendAuthenticationFailure.BACKEND_UNAVAILABLE);
    }

    @Test
    void 리다이렉트_주소로_인증정보를_전송하지_않는다() {
        status = 302;
        assertFailure(client, BackendAuthenticationFailure.INVALID_RESPONSE);
        assertThat(requests).hasValue(1);
        assertThat(path).isEqualTo("/api/internal/websocket/authentication");
    }

    private BackendAuthenticationClient createClient(URI url, String key, Duration readTimeout) {
        var properties = new BackendAuthenticationProperties(url, key, Duration.ofSeconds(1), readTimeout);
        var config = new BackendAuthenticationClientConfig();
        var httpClient = config.backendAuthenticationHttpClient(properties);
        httpClients.add(httpClient);
        return new BackendAuthenticationClient(config.backendAuthenticationRestClient(
                RestClient.builder(), httpClient, properties), properties);
    }

    private void assertFailure(BackendAuthenticationClient target, BackendAuthenticationFailure expected) {
        var error = catchThrowableOfType(BackendAuthenticationException.class,
                () -> target.authenticate("test-access-token", "test-browser-context"));
        assertThat(error.failure()).isEqualTo(expected);
        assertThat(error.getMessage()).isEqualTo(expected.name());
        assertThat(error.getCause()).isNull();
    }

    private ObjectNode validBody() {
        var root = mapper.createObjectNode();
        root.put("message", "WEBSOCKET_AUTHENTICATION_SUCCESS");
        root.putObject("data").put("userId", 42L).put("sessionId", "test-session")
                .put("accessTokenExpiresAt", Instant.now().plusSeconds(600).toString())
                .put("csrfToken", "existing-test-csrf");
        return root;
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        method = exchange.getRequestMethod();
        path = exchange.getRequestURI().getPath();
        serviceToken = exchange.getRequestHeaders().getFirst("X-Internal-Service-Token");
        cookies = exchange.getRequestHeaders().getFirst("Cookie");
        try (exchange) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 302) {
                exchange.getResponseHeaders().set("Location", baseUrl + "/redirect-target");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 || bytes.length == 0 ? -1 : bytes.length);
            if (status != 204 && bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }
}
