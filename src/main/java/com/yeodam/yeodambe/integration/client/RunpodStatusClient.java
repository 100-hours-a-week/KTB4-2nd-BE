package com.yeodam.yeodambe.integration.client;

import com.yeodam.yeodambe.common.exception.RunpodStatusUnavailableException;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;

@Slf4j
@Component
public class RunpodStatusClient {
    private final RestClient restClient;

    public RunpodStatusClient(
            RestClient.Builder builder,
            @Value("${ai.server.base-url}") String baseUrl,
            @Value("${ai.server.api-key}") String apiKey,
            @Value("${ai.server.runpod-status-connection-timeout}") Duration connectionTimeout,
            @Value("${ai.server.runpod-status-read-timeout}") Duration readTimeout
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

    public RunpodStatusResponse getStatus() {
        long startedAt = System.nanoTime();
        try {
            JsonNode response = restClient.get()
                    .uri("/runpod/status")
                    .headers(headers -> {
                        String requestId = MDC.get("request_id");

                        if (requestId != null) {
                            headers.set("X-Request-ID", requestId);
                        }
                    })
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.isNull()) {
                return null;
            }

            return toStatusResponse(response);

        } catch (RestClientException | RunpodStatusUnavailableException failure) {
            log.atWarn()
                    .addKeyValue("stage", "runpod_status")
                    .addKeyValue("failure", failure.getClass().getSimpleName())
                    .addKeyValue("duration_ms", (System.nanoTime() - startedAt) / 1_000_000)
                    .addKeyValue("request_id", MDC.get("request_id"))
                    .log("AI 서버의 RunPod 상태 조회에 실패했습니다.");

            throw new RunpodStatusUnavailableException();
        }
    }

    private RunpodStatusResponse toStatusResponse(JsonNode response) {
        JsonNode running = response.get("running");
        JsonNode ready = response.get("ready");

        boolean validRunning = running != null && (running.isNull() || running.isBoolean());

        if (!validRunning || ready == null || !ready.isBoolean()) {
            throw new RunpodStatusUnavailableException();
        }

        return new RunpodStatusResponse(
                running.isNull() ? null : running.asBoolean(),
                ready.asBoolean()
        );
    }

}
