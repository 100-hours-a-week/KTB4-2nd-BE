package com.yeodam.yeodambe.integration.client;

import com.yeodam.yeodambe.integration.exception.IntegrationInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.exception.AiQueryTimeoutException;
import com.yeodam.yeodambe.integration.service.request.AiQueryParseRequest;
import com.yeodam.yeodambe.integration.service.request.AiQuerySearchRequest;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse;
import com.yeodam.yeodambe.integration.service.response.AiQuerySearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class AiQueryClient {
    private final RestClient restClient;

    public AiQueryClient(
            RestClient.Builder builder,
            @Value("${ai.server.base-url}") String baseUrl,
            @Value("${ai.server.api-key}") String apiKey,
            @Value("${ai.server.query-connection-timeout}") Duration connectionTimeout,
            @Value("${ai.server.query-read-timeout}") Duration readTimeout
    ) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(connectionTimeout)
                        .build()
        );
        factory.setReadTimeout(readTimeout);
        restClient = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    public AiQueryParseResponse parse(AiQueryParseRequest request) {
        return post("parse", request, AiQueryParseResponse.class);
    }

    public AiQuerySearchResponse search(AiQuerySearchRequest request) {
        return post("search", request, AiQuerySearchResponse.class);
    }

    private <T> T post(String stage, Object request, Class<T> responseType) {
        long startedAt = System.nanoTime();
        try (WorkflowLog.Stage timing = startRequestLogging(stage)) {
            logRequestStarted(stage);
            T response = restClient.post()
                    .uri("/query/" + stage)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::setRequestIdHeader)
                    .body(request)
                    .retrieve()
                    .body(responseType);
            if (response == null) {
                throw new AiQueryUnavailableException();
            }
            logRequestCompleted(timing);
            return response;
        } catch (RestClientException | AiQueryUnavailableException failure) {
            logRequestFailure(stage, startedAt, failure);
            // 외부 예외의 URL·본문·인증키가 공통 로그로 전달되지 않도록 cause를 제거합니다.
            if (isTimeout(failure)) {
                throw new AiQueryTimeoutException();
            }
            throw new AiQueryUnavailableException();
        }
    }

    private WorkflowLog.Stage startRequestLogging(String stage) {
        return WorkflowLog.stage("ai_query_" + stage).field("workflow", "natural_language_search");
    }

    private void logRequestCompleted(WorkflowLog.Stage timing) {
        timing.success();
    }

    private void logRequestFailure(String stage, long startedAt, RuntimeException failure) {
        log.atWarn()
                .addKeyValue("stage", stage)
                .addKeyValue("failure", failure.getClass().getSimpleName())
                .addKeyValue("duration_ms", (System.nanoTime() - startedAt) / 1_000_000)
                .addKeyValue("request_id", MDC.get("request_id"))
                .log(IntegrationInternalErrorMessage.AI_QUERY_HTTP_REQUEST_FAILED.message());
    }

    private void setRequestIdHeader(HttpHeaders headers) {
        String requestId = MDC.get("request_id");
        if (requestId != null) {
            headers.set("X-Request-ID", requestId);
        }
    }

    private void logRequestStarted(String stage) {
        log.atInfo()
                .addKeyValue("event", "ai_query_request_started")
                .addKeyValue("workflow", "natural_language_search")
                .addKeyValue("stage", stage)
                .log("AI 검색 요청을 시작합니다.");
    }

    private boolean isTimeout(Throwable failure) {
        if (failure instanceof RestClientResponseException responseFailure) {
            return responseFailure.getStatusCode().value() == 504;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
