package com.yeodam.yeodambe.common.monitoring;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=9091"
)
@ActiveProfiles({"prod", "test", "internal-metrics"})
@Import(TestcontainersConfiguration.class)
class InternalMetricsIntegrationTest {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    @Test
    void 관리_포트에서_실제_프로메테우스_지표를_수집한다() throws Exception {
        HttpResponse<String> response = get(9091, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void 서비스_포트에는_프로메테우스_지표를_공개하지_않는다() throws Exception {
        HttpResponse<String> response = get(serverPort, "/api/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("jvm_memory_used_bytes");
    }

    @Test
    void 기존_배포_헬스체크_경로와_상세정보_비공개를_유지한다() throws Exception {
        HttpResponse<String> response = get(serverPort, "/api/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).get("status").asString()).isEqualTo("UP");
        assertThat(response.body()).doesNotContain("components", "details");
    }

    @Test
    void 기존_헬스체크는_장애_발생_시_503을_유지한다() throws Exception {
        HealthIndicator failedIndicator = () -> Health.down().withDetail("secret", "hidden").build();
        healthContributorRegistry.registerContributor("metricsTestFailure", failedIndicator);
        try {
            HttpResponse<String> response = get(serverPort, "/api/actuator/health");

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(objectMapper.readTree(response.body()).get("status").asString()).isEqualTo("DOWN");
            assertThat(response.body()).doesNotContain("components", "details", "secret", "hidden");
        } finally {
            healthContributorRegistry.unregisterContributor("metricsTestFailure");
        }
    }

    @Test
    void 관리_포트에서는_일반_API를_사용할_수_없다() throws Exception {
        HttpResponse<String> response = get(9091, "/auth/csrf");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
