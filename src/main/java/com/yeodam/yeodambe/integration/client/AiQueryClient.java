package com.yeodam.yeodambe.integration.client;

import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
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

import java.net.http.HttpClient;
import java.time.Duration;

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
        try {
            T response = restClient.post()
                    .uri("/query/" + stage)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        String requestId = MDC.get("request_id");
                        if (requestId != null) {
                            headers.set("X-Request-ID", requestId);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(responseType);
            if (response == null) {
                throw new AiQueryUnavailableException();
            }
            return response;
        } catch (RestClientException | AiQueryUnavailableException failure) {
            log.atWarn()
                    .addKeyValue("stage", stage)
                    .addKeyValue("failure", failure.getClass().getSimpleName())
                    .addKeyValue("duration_ms", (System.nanoTime() - startedAt) / 1_000_000)
                    .addKeyValue("request_id", MDC.get("request_id"))
                    .log("AI 검색 HTTP 요청에 실패했습니다.");
            // 외부 예외의 URL·본문·인증키가 공통 로그로 전달되지 않도록 cause를 제거합니다.
            throw new AiQueryUnavailableException();
        }
    }
}
