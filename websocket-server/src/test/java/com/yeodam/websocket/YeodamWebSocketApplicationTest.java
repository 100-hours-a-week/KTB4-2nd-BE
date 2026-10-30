package com.yeodam.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "websocket.backend-auth.service-token=application-test-service-token"
)
class YeodamWebSocketApplicationTest extends RedisTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Test
    void 헬스_엔드포인트는_api_경로를_사용하고_상세정보를_숨긴다() throws Exception {
        var response = get("/api/actuator/health");
        var body = new ObjectMapper().readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asString()).isEqualTo("UP");
        assertThat(body.has("details")).isFalse();
        assertThat(body.has("components")).isFalse();
        assertThat(get("/actuator/health").statusCode()).isEqualTo(404);
    }

    @Test
    void 헬스_외의_미인증_요청을_거절한다() throws Exception {
        assertThat(get("/api/actuator/prometheus").statusCode()).isEqualTo(401);
        assertThat(get("/api/ws").statusCode()).isEqualTo(401);
        assertThat(get("/api/login").statusCode()).isEqualTo(401);
    }

    @Test
    void 백엔드_앱과_DB_스토리지_클라이언트_없이_시작한다() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        for (String className : new String[]{
                "com.yeodam.yeodambe.YeodamBeApplication",
                "jakarta.persistence.EntityManagerFactory",
                "software.amazon.awssdk.services.s3.S3Client",
                "org.springframework.kafka.core.KafkaTemplate"
        }) {
            assertThat(ClassUtils.isPresent(className, context.getClassLoader()))
                    .as("독립 앱에 포함되지 않아야 하는 클래스: %s", className)
                    .isFalse();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
