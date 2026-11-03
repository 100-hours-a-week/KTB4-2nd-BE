package com.yeodam.yeodambe.story.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewFailedException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewInProgressException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewTimeoutException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewUnavailableException;
import com.yeodam.yeodambe.story.client.request.StoryTonePreviewAiRequest;
import com.yeodam.yeodambe.story.client.response.StoryTonePreviewAiResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class StoryTonePreviewAiClient {
    private final RestClient restClient;

    public StoryTonePreviewAiClient(
            RestClient.Builder builder,
            @Value("${ai.server.base-url}") String baseUrl,
            @Value("${ai.server.api-key}") String apiKey,
            @Value("${ai.server.story-connection-timeout}")
            Duration connectionTimeout,
            @Value("${ai.server.story-preview-read-timeout:90s}")
            Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectionTimeout)
                .build();

        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);

        this.restClient = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey
                )
                .build();
    }

    public StoryTonePreviewAiResponse preview(
            Long tripId,
            StoryTonePreviewAiRequest request
    ) {
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/trips/{tripId}/story/rewrite", tripId)
                    .contentType(MediaType.APPLICATION_JSON);

            String requestId = MDC.get("request_id");
            if (requestId != null && !requestId.isBlank()) {
                requestSpec.header("X-Request-ID", requestId);
            }

            StoryTonePreviewAiResponse response = requestSpec
                    .body(request)
                    .retrieve()
                    .body(StoryTonePreviewAiResponse.class);

            if (response == null) {
                throw new StoryTonePreviewFailedException();
            }

            return response;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();

            if (status == 409) {
                throw new StoryTonePreviewInProgressException();
            }
            if (status == 503) {
                throw new StoryTonePreviewUnavailableException();
            }
            if (status == 504) {
                throw new StoryTonePreviewTimeoutException();
            }

            throw new StoryTonePreviewFailedException(exception);
        } catch (ResourceAccessException exception) {
            if (isReadTimeout(exception)) {
                throw new StoryTonePreviewTimeoutException();
            }

            throw new StoryTonePreviewUnavailableException();
        } catch (RestClientException exception) {
            throw new StoryTonePreviewFailedException(exception);
        }
    }

    private boolean isReadTimeout(Throwable failure) {
        Throwable cause = failure;

        while (cause != null) {
            if (cause instanceof HttpConnectTimeoutException) {
                return false;
            }

            if (cause instanceof HttpTimeoutException
                    || cause instanceof SocketTimeoutException) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}