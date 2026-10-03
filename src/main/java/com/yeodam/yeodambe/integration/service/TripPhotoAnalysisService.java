package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.exception.IntegrationInternalErrorMessage;
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
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;


import lombok.extern.slf4j.Slf4j;

@Slf4j
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
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(connectionTimeout)
                        .build()
        );
        JdkClientHttpRequestFactory healthFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
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

    public JsonNode analyze(
            Long tripId,
            String executionId,
            TripPhotoAnalysisRequest request,
            BooleanSupplier analysisStarted
    ) {
        long workerReadyStartedAt = System.nanoTime();
        String workerFailureStage = "ec2_start";
        long readinessStageStarted = System.nanoTime();

        log.atInfo()
                .addKeyValue("event", "worker_ready")
                .addKeyValue("result", "started")
                .addKeyValue("trip_id", tripId)
                .addKeyValue("job_id", executionId)
                .log("AI Worker 준비를 시작했습니다.");

        try {
            long ec2Started = readinessStageStarted;
            starter.ensureRunning();
            traceBoundary(executionId, "ai_ec2_ready", ec2Started, "success");

            workerFailureStage = "health_check";
            long healthStarted = System.nanoTime();
            readinessStageStarted = healthStarted;
            waitUntilReady();
            traceBoundary(executionId, "ai_health_ready", healthStarted, "success");

            log.atInfo()
                    .addKeyValue("event", "worker_ready")
                    .addKeyValue("result", "success")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("duration_ms", elapsedMillis(workerReadyStartedAt))
                    .log("AI Worker 준비를 완료했습니다.");
        } catch (RuntimeException failure) {
            traceBoundary(executionId, workerFailureStage.equals("ec2_start") ? "ai_ec2_ready" : "ai_health_ready", readinessStageStarted, "failure");
            log.atError()
                    .addKeyValue("event", "worker_ready")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("duration_ms", elapsedMillis(workerReadyStartedAt))
                    .addKeyValue("failure_stage", workerFailureStage)
                    .addKeyValue("error_code", "INTERNAL_SERVER_ERROR")
                    .log("AI Worker 준비에 실패했습니다.", failure);
            throw failure;
        }

        requireAnalysisStart(analysisStarted);

        long aiJobStartedAt = System.nanoTime();

        log.atInfo()
                .addKeyValue("event", "ai_job")
                .addKeyValue("result", "started")
                .addKeyValue("trip_id", tripId)
                .addKeyValue("job_id", executionId)
                .log("AI 사진 분석을 요청했습니다.");

        JsonNode response;

        try {
            traceBoundary(executionId, "ai_request_start", System.nanoTime(), "success");
            response = restClient.post()
                    .uri("/trips/{tripId}/process", tripId)
                    .headers(this::setAiHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException failure) {
            log.atError()
                    .addKeyValue("event", "ai_job")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("duration_ms", elapsedMillis(aiJobStartedAt))
                    .addKeyValue("failure_stage", "ai_request")
                    .addKeyValue("error_code", "INTERNAL_SERVER_ERROR")
                    .log("AI 사진 분석 요청에 실패했습니다.", failure);
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_REQUEST_FAILED.message(), failure);
        }

        try {
            JsonNode result = validateResponse(tripId, executionId, response);

            log.atInfo()
                    .addKeyValue("event", "ai_job")
                    .addKeyValue("result", "success")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("duration_ms", elapsedMillis(aiJobStartedAt))
                    .log("AI 사진 분석을 완료했습니다.");

            return result;
        } catch (AiProcessingFailedException failure) {
            log.atWarn()
                    .addKeyValue("event", "ai_job")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("duration_ms", elapsedMillis(aiJobStartedAt))
                    .addKeyValue("failure_stage", "ai_request")
                    .addKeyValue("error_code", failure.getCode())
                    .log("AI 사진 분석이 실패했습니다.", failure);
            throw failure;
        } catch (RuntimeException failure) {
            log.atError()
                    .addKeyValue("event", "ai_job")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("duration_ms", elapsedMillis(aiJobStartedAt))
                    .addKeyValue("failure_stage", "result_validation")
                    .addKeyValue("error_code", "INTERNAL_SERVER_ERROR")
                    .log("AI 사진 분석 응답 검증에 실패했습니다.", failure);
            throw failure;
        }
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
                    .headers(this::setAiHeaders)
                    .retrieve()
                    .body(JsonNode.class);
            return validateStatusResponse(tripId, response);
        } catch (ResourceAccessException e) {
            throw new AiStatusUnavailableException(e);
        } catch (RestClientException e) {
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_STATUS_LOOKUP_FAILED.message(), e);
        }
    }

    public void cancel(Long tripId) {
        JsonNode response;
        try {
            response = restClient.delete()
                    .uri("/trips/{tripId}/process", tripId)
                    .headers(this::setAiHeaders)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_CANCEL_FAILED.message(), e);
        }
        validateCancelResponse(tripId, response);
    }

    static void validateCancelResponse(Long tripId, JsonNode response) {
        String status = response == null ? "" : response.path("status").asString();
        if (response == null
                || !response.path("trip_id").isIntegralNumber()
                || response.path("trip_id").asLong(-1) != tripId
                || !("CANCELED".equals(status) || "COMPLETED".equals(status) || "FAILED".equals(status))) {
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_CANCEL_RESPONSE_INVALID.message());
        }
    }

    static JsonNode validateResponse(Long tripId, String executionId, JsonNode response) {
        if (response == null
                || response.path("trip_id").asLong(-1) != tripId
                || !executionId.equals(response.path("execution_id").asString())) {
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_RESULT_INVALID.message());
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
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_RESULT_INVALID.message());
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
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_RESULT_INVALID.message());
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
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_STATUS_INVALID.message());
        }

        TripPhotoAnalysisStatusResponse.Status status;
        try {
            status = TripPhotoAnalysisStatusResponse.Status.valueOf(response.path("status").asString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_STATUS_INVALID.message(), e);
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
            throw new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_STATUS_INVALID.message());
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

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private void setAiHeaders(HttpHeaders headers) {
        headers.setBearerAuth(apiKey);

        String requestId = MDC.get("request_id");
        if (requestId != null) {
            headers.set("X-Request-ID", requestId);
        }
    }

    private void traceBoundary(String executionId, String stage, long started, String outcome) {
        if (System.getProperty("load.runDir") == null) return;
        long ended = stage.equals("ai_request_start") ? started : System.nanoTime();
        log.atInfo().addKeyValue("event", "image_worker").addKeyValue("worker", java.util.Map.of(
                "batch_id", java.util.Objects.requireNonNullElse(MDC.get("request_id"), "unassociated"),
                "execution_id", executionId, "stage", stage, "start_ns", started, "end_ns", ended,
                "epoch_ms", System.currentTimeMillis(), "outcome", outcome, "photo_count", 0))
                .log("AI 요청 준비 단계");
    }

    private void waitUntilReady() {
        Instant deadline = Instant.now().plusSeconds(180);

        while (Instant.now().isBefore(deadline)) {
            try {
                JsonNode health = healthClient.get()
                        .uri("/health")
                        .headers(this::setAiHeaders)
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
                    throw new IllegalStateException(IntegrationInternalErrorMessage.AI_SERVER_HEALTH_CHECK_FAILED.message());
                }
            } catch (ResourceAccessException ignored) {
                // 기동 중 연결실패: 5초 뒤 다시 확인
            }

            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(IntegrationInternalErrorMessage.AI_SERVER_READY_WAIT_INTERRUPTED.message(), e);
            }
        }
        throw new IllegalStateException(IntegrationInternalErrorMessage.AI_SERVER_READY_WAIT_TIMEOUT.message());
    }

}
