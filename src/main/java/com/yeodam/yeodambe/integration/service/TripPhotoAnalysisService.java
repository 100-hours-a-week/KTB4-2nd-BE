package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.integration.client.AiEc2Starter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

@Service
public class TripPhotoAnalysisService {
    private final RestClient restClient;
    private final String apiKey;
    private final RestClient healthClient;
    private final AiEc2Starter starter;

    public TripPhotoAnalysisService(
            RestClient.Builder builder,
            AiEc2Starter starter,
            @Value("${ai.server.base-url}") String baseUrl,
            @Value("${ai.server.api-key}") String apiKey,
            @Value("${ai.server.connection-timeout}") Duration connectionTimeout,
            @Value("${ai.server.analysis_timeout}") Duration analysisTimeout,
            @Value("${ai.server.health-connection-timeout}") Duration healthConnectionTimeout,
            @Value("${ai.server.health_read_timeout}") Duration healthReadTimeout
    ) {
        this.starter = starter;
        JdkClientHttpRequestFactory analysisFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(connectionTimeout)
                        .build()
        );
        JdkClientHttpRequestFactory healthFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(healthConnectionTimeout)
                        .build()
        );

        analysisFactory.setReadTimeout(analysisTimeout);
        healthFactory.setReadTimeout(healthReadTimeout);

        this.restClient = builder.baseUrl(baseUrl)
                .requestFactory(analysisFactory)
                .build();
        this.healthClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(healthFactory)
                .build();
        this.apiKey = apiKey;
    }

    public JsonNode analyze(Long tripId, String executionId, TripPhotoAnalysisRequest request) {
        JsonNode response;

        starter.ensureRunning();
        waitUntilReady();

        try {
            response = restClient.post()
                    .uri("/trips/{tripId}/process", tripId)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("AI 사진 분석 호출에 실패했습니다.", e);
        }

        return validateResponse(tripId, executionId, response);
    }

    static JsonNode validateResponse(Long tripId, String executionId, JsonNode response) {
        if (response == null
                || response.path("trip_id").asLong(-1) != tripId
                || !executionId.equals(response.path("execution_id").asString())
                || !"COMPLETED".equals(response.path("status").asString())
                || !response.path("result").isObject()
                || !response.path("result").path("places").isArray()
                || !response.path("result").path("unclassified").isArray()
                || !response.path("result").path("failed").isArray()
                || !response.path("result").path("failed").isEmpty()) {
            throw new IllegalStateException("AI 사진 분석 결과가 올바르지 않습니다.");
        }
        return response.path("result");
    }

    private void waitUntilReady() {
        Instant deadline = Instant.now().plusSeconds(180);

        while (Instant.now().isBefore(deadline)) {
            try {
                JsonNode health = healthClient.get()
                        .uri("/health")
                        .headers(headers -> headers.setBearerAuth(apiKey))
                        .retrieve()
                        .body(JsonNode.class);

                if (
                        health != null
                                && "ok".equals(health.path("status").asString())
                                && health.path("model_loaded").asBoolean(false)
                ) {
                    return;
                }
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != HttpStatus.SERVICE_UNAVAILABLE.value()) {
                    throw new IllegalStateException("AI 서버 상태 확인에 실패했습니다.");
                }
            } catch (ResourceAccessException ignored) {
                // 기동 중 연결실패: 5초 뒤 다시 확인
            }

            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AI 서버 준비 대기가 중단됐습니다", e);
            }
        }
        throw new IllegalStateException("AI 서버 준비 시간이 초과됐습니다.");
    }

}
