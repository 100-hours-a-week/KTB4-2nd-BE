package com.yeodam.yeodambe.common.monitoring;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.ReactiveHealthContributor;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.health.registry.ReactiveHealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

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

    @Autowired
    private ReactiveHealthContributorRegistry reactiveHealthContributorRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    @ParameterizedTest
    @ValueSource(strings = {"liveness", "readiness"})
    void 관리_포트의_Probe는_인증_없이_상세정보를_숨겨_응답한다(String probe) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:9091/actuator/health/" + probe))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", "accessToken=invalid-token")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).get("status").asString()).isEqualTo("UP");
        assertThat(response.body()).doesNotContain("components", "details");
    }

    @Test
    void DB_장애는_Readiness만_실패시킨다() throws Exception {
        String dependency = "db";
        HealthContributor original = healthContributorRegistry.getContributor(dependency);
        assertThat(original).isNotNull();
        healthContributorRegistry.unregisterContributor(dependency);
        healthContributorRegistry.registerContributor(dependency,
                (HealthIndicator) () -> Health.down().withDetail("secret", "hidden").build());
        try {
            HttpResponse<String> readiness = get(9091, "/actuator/health/readiness");
            HttpResponse<String> liveness = get(9091, "/actuator/health/liveness");

            assertThat(readiness.statusCode()).isEqualTo(503);
            assertThat(objectMapper.readTree(readiness.body()).get("status").asString()).isEqualTo("DOWN");
            assertThat(readiness.body()).doesNotContain("components", "details", "secret", "hidden");
            assertThat(liveness.statusCode()).isEqualTo(200);
        } finally {
            healthContributorRegistry.unregisterContributor(dependency);
            healthContributorRegistry.registerContributor(dependency, original);
        }
    }

    @Test
    void 인증_Redis_장애는_Readiness만_실패시킨다() throws Exception {
        ReactiveHealthContributor original = reactiveHealthContributorRegistry.getContributor("redis");
        assertThat(original).isNotNull();
        reactiveHealthContributorRegistry.unregisterContributor("redis");
        reactiveHealthContributorRegistry.registerContributor("redis",
                (ReactiveHealthIndicator) () -> Mono.just(Health.down().build()));
        try {
            assertThat(get(9091, "/actuator/health/readiness").statusCode()).isEqualTo(503);
            assertThat(get(9091, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        } finally {
            reactiveHealthContributorRegistry.unregisterContributor("redis");
            reactiveHealthContributorRegistry.registerContributor("redis", original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "ai", "externalApi", "attachmentBacklog"})
    void 판정_대상_밖의_장애는_Probe에_영향을_주지_않는다(String dependency) throws Exception {
        healthContributorRegistry.registerContributor(dependency, (HealthIndicator) () -> Health.down().build());
        try {
            assertThat(get(9091, "/actuator/health/liveness").statusCode()).isEqualTo(200);
            assertThat(get(9091, "/actuator/health/readiness").statusCode()).isEqualTo(200);
            assertThat(get(9091, "/actuator/health").statusCode()).isEqualTo(503);
        } finally {
            healthContributorRegistry.unregisterContributor(dependency);
        }
    }

    @Test
    void 요청_거부_상태는_Readiness만_실패시킨다() throws Exception {
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
        try {
            assertThat(get(9091, "/actuator/health/readiness").statusCode()).isEqualTo(503);
            assertThat(get(9091, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        } finally {
            AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    void 생존_상태가_비정상이면_Liveness가_실패한다() throws Exception {
        AvailabilityChangeEvent.publish(applicationContext, LivenessState.BROKEN);
        try {
            assertThat(get(9091, "/actuator/health/liveness").statusCode()).isEqualTo(503);
        } finally {
            AvailabilityChangeEvent.publish(applicationContext, LivenessState.CORRECT);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"liveness", "readiness"})
    void 서비스_포트에는_Probe를_인증_없이_공개하지_않는다(String probe) throws Exception {
        assertThat(get(serverPort, "/api/actuator/health/" + probe).statusCode()).isEqualTo(401);
    }

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
