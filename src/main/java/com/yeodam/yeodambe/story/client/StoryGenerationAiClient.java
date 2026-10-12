package com.yeodam.yeodambe.story.client;

import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.exception.StoryGenerationAiRequestException;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class StoryGenerationAiClient {
    private final RestClient restClient;

    public StoryGenerationAiClient(
            RestClient.Builder builder,
            @Value("${ai.server.base-url}") String baseUrl,
            @Value("${ai.server.api-key}") String apiKey,
            @Value("${ai.server.story-connection-timeout}") Duration connectionTimeout,
            @Value("${ai.server.story-read-timeout}") Duration readTimeout
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
    public StoryGenerationAiResponse getStatus(Long tripId) {
        return request(
                HttpMethod.GET,
                tripId,
                StoryGenerationInternalErrorMessage.AI_STATUS_LOOKUP_FAILED
        );
    }

    public StoryGenerationAiResponse cancel(Long tripId) {
        return request(
                HttpMethod.DELETE,
                tripId,
                StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED
        );
    }

    private StoryGenerationAiResponse request(
            HttpMethod method,
            Long tripId,
            StoryGenerationInternalErrorMessage failureMessage
    ) {
        try {
            StoryGenerationAiResponse response = restClient.method(method)
                    .uri("/trips/{tripId}/story", tripId)
                    .headers(headers -> {
                        String requestId = MDC.get("request_id");
                        if (requestId != null) {
                            headers.set("X-Request-ID", requestId);
                        }
                    })
                    .retrieve()
                    .body(StoryGenerationAiResponse.class);

            if (response == null) {
                throw new StoryGenerationAiRequestException(
                        StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID,
                        null,
                        null
                );
            }

            return response;
        } catch (RestClientResponseException failure) {
            throw new StoryGenerationAiRequestException(
                    failureMessage,
                    failure.getStatusCode().value(),
                    readErrorCode(failure)
            );
        } catch (RestClientException failure) {
            throw new StoryGenerationAiRequestException(
                    failureMessage,
                    null,
                    null
            );
        }
    }

    private String readErrorCode(RestClientResponseException failure) {
        try {
            StoryGenerationAiResponse response =
                    failure.getResponseBodyAs(StoryGenerationAiResponse.class);

            if (response == null || response.error() == null) {
                return null;
            }

            return response.error().code();
        } catch (RestClientException unreadableBody) {
            return null;
        }
    }
}