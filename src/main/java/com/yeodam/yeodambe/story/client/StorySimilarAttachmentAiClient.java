package com.yeodam.yeodambe.story.client;

import com.yeodam.yeodambe.story.client.request.StorySimilarAttachmentAiRequest;
import com.yeodam.yeodambe.story.client.response.StorySimilarAttachmentAiResponse;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentAiRequestException;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentInternalErrorMessage;
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

import java.net.http.HttpClient;
import java.time.Duration;

@Slf4j
@Component
public class StorySimilarAttachmentAiClient {
    private final RestClient restClient;

    public StorySimilarAttachmentAiClient(
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

    public StorySimilarAttachmentAiResponse findSimilar(
            Long tripId,
            StorySimilarAttachmentAiRequest request
    ) {
        try {
            StorySimilarAttachmentAiResponse response = restClient.post()
                    .uri("/trips/{tripId}/attachments/similar", tripId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        String requestId = MDC.get("request_id");
                        if (requestId != null) {
                            headers.set("X-Request-ID", requestId);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(StorySimilarAttachmentAiResponse.class);

            if (response == null) {
                throw new StorySimilarAttachmentAiRequestException(
                        StorySimilarAttachmentInternalErrorMessage.AI_RESPONSE_INVALID
                );
            }

            return response;
        } catch (RestClientException failure) {
            log.atWarn()
                    .addKeyValue("event", "story_similar_attachment_ai_request_failed")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("failure", failure.getClass().getSimpleName())
                    .addKeyValue("http_status",
                            failure instanceof RestClientResponseException responseFailure
                                    ? responseFailure.getStatusCode().value() : null)
                    .log(StorySimilarAttachmentInternalErrorMessage.AI_REQUEST_FAILED.message());

            throw new StorySimilarAttachmentAiRequestException(
                    StorySimilarAttachmentInternalErrorMessage.AI_REQUEST_FAILED
            );
        }
    }
}
