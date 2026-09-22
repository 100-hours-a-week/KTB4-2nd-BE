package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.common.exception.AiStatusUnavailableException;
import com.yeodam.yeodambe.common.exception.AiProcessingFailedException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
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
import java.util.function.BooleanSupplier;

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

    public JsonNode analyze(Long tripId, String executionId, TripPhotoAnalysisRequest request,
                            BooleanSupplier analysisStarted) {
        JsonNode response;

        starter.ensureRunning();
        waitUntilReady();
        requireAnalysisStart(analysisStarted);

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

    static void requireAnalysisStart(BooleanSupplier analysisStarted) {
        if (!analysisStarted.getAsBoolean()) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
    }

    public TripPhotoAnalysisStatusResponse findStatus(Long tripId) {
        try {
            JsonNode response = restClient.get()
                    .uri("/trips/{tripId}/process", tripId)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .retrieve()
                    .body(JsonNode.class);
            return validateStatusResponse(tripId, response);
        } catch (ResourceAccessException e) {
            throw new AiStatusUnavailableException(e);
        } catch (RestClientException e) {
            throw new IllegalStateException("AI 사진 분석 상태 조회에 실패했습니다.", e);
        }
    }

    public void cancel(Long tripId) {
        JsonNode response;
        try {
            response = restClient.delete()
                    .uri("/trips/{tripId}/process", tripId)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("AI 사진 분석 취소 호출에 실패했습니다.", e);
        }
        validateCancelResponse(tripId, response);
    }

    static void validateCancelResponse(Long tripId, JsonNode response) {
        String status = response == null ? "" : response.path("status").asString();
        if (response == null
                || !response.path("trip_id").isIntegralNumber()
                || response.path("trip_id").asLong(-1) != tripId
                || !("CANCELED".equals(status) || "COMPLETED".equals(status) || "FAILED".equals(status))) {
            throw new IllegalStateException("AI 사진 분석 취소 응답이 올바르지 않습니다.");
        }
    }

    static JsonNode validateResponse(Long tripId, String executionId, JsonNode response) {
        if (response == null
                || response.path("trip_id").asLong(-1) != tripId
                || !executionId.equals(response.path("execution_id").asString())) {
            throw new IllegalStateException("AI 사진 분석 결과가 올바르지 않습니다.");
        }

        if ("FAILED".equals(response.path("status").asString())) {
            throw failedResponse(tripId, response);
        }
        if ("CANCELED".equals(response.path("status").asString())) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        if (!"COMPLETED".equals(response.path("status").asString())
                || !response.path("result").isObject()
                || !response.path("result").path("places").isArray()
                || !response.path("result").path("unclassified").isArray()
                || !response.path("result").path("failed").isArray()
                || !response.path("result").path("failed").isEmpty()) {
            throw new IllegalStateException("AI 사진 분석 결과가 올바르지 않습니다.");
        }
        return response.path("result");
    }

    private static AiProcessingFailedException failedResponse(Long tripId, JsonNode response) {
        JsonNode progress = response.path("progress");
        JsonNode done = progress.path("done");
        JsonNode total = progress.path("total");
        JsonNode currentStep = response.path("current_step");
        JsonNode error = response.path("error");
        String code = error.path("code").asString();
        String message = error.path("message").asString();

        if (!progress.isObject()
                || !done.isIntegralNumber()
                || !total.isIntegralNumber()
                || done.asInt(-1) < 0
                || total.asInt(-1) < 0
                || done.asInt() > total.asInt()
                || (!currentStep.isString() && !currentStep.isNull())
                || !response.path("result").isNull()
                || !error.isObject()
                || code.isBlank()
                || message.isBlank()) {
            throw new IllegalStateException("AI 사진 분석 결과가 올바르지 않습니다.");
        }

        return new AiProcessingFailedException(
                tripId,
                done.asInt(),
                total.asInt(),
                currentStep.isNull() ? null : currentStep.asString(),
                code,
                message
        );
    }

    static TripPhotoAnalysisStatusResponse validateStatusResponse(Long tripId, JsonNode response) {
        JsonNode responseTripId = response == null ? null : response.path("trip_id");
        if (responseTripId == null
                || !responseTripId.isIntegralNumber()
                || responseTripId.asLong(-1) != tripId) {
            throw new IllegalStateException("AI 사진 분석 상태가 올바르지 않습니다.");
        }

        TripPhotoAnalysisStatusResponse.Status status;
        try {
            status = TripPhotoAnalysisStatusResponse.Status.valueOf(response.path("status").asString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AI 사진 분석 상태가 올바르지 않습니다.", e);
        }

        JsonNode progress = response.path("progress");
        JsonNode doneNode = progress.path("done");
        JsonNode totalNode = progress.path("total");
        int done = doneNode.asInt(-1);
        int total = totalNode.asInt(-1);
        JsonNode result = response.path("result");
        JsonNode error = response.path("error");
        JsonNode currentStep = response.path("current_step");

        boolean fieldsValid = switch (status) {
            case QUEUED -> currentStep.isNull() && result.isNull() && error.isNull();
            case PROCESSING -> result.isNull() && error.isNull();
            case COMPLETED -> result.isObject() && error.isNull();
            case FAILED -> result.isNull() && error.isObject();
            case CANCELED -> result.isNull() && error.isNull();
        };
        boolean progressValid = status == TripPhotoAnalysisStatusResponse.Status.QUEUED
                || (progress.isObject()
                && doneNode.isIntegralNumber()
                && totalNode.isIntegralNumber()
                && done >= 0 && total >= 0 && done <= total);

        if (!progressValid
                || (!currentStep.isString() && !currentStep.isNull())
                || !fieldsValid) {
            throw new IllegalStateException("AI 사진 분석 상태가 올바르지 않습니다.");
        }

        return new TripPhotoAnalysisStatusResponse(
                tripId,
                status,
                status == TripPhotoAnalysisStatusResponse.Status.QUEUED
                        ? null
                        : new TripPhotoAnalysisStatusResponse.Progress(done, total),
                currentStep.isNull() ? null : currentStep.asString(),
                result,
                error
        );
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
