package com.yeodam.websocket.trip.client;

import com.yeodam.websocket.common.websocket.config.BackendAuthenticationProperties;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;

@Component
public class BackendTripEditClient {

    private final RestClient restClient;
    private final BackendAuthenticationProperties properties;

    public BackendTripEditClient(
            @Qualifier("backendAuthenticationRestClient") RestClient restClient,
            BackendAuthenticationProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public Instant checkAccess(long tripId, long userId, WebSocketSessionCredentials credentials) {
        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri("/api/internal/websocket/trips/{tripId}/access", tripId);
        authenticate(request, credentials);
        JsonNode response = request.retrieve().body(JsonNode.class);
        JsonNode data = response == null ? null : response.get("data");
        Instant now = Instant.now();
        if (response == null || !"TRIP_WEBSOCKET_ACCESS_FOUND".equals(response.path("message").asString())
                || data == null || !data.path("tripId").isIntegralNumber()
                || !data.path("tripId").canConvertToLong() || data.path("tripId").asLong() != tripId
                || !data.path("userId").isIntegralNumber() || !data.path("userId").canConvertToLong()
                || data.path("userId").asLong() != userId
                || !Set.of("OWNER", "READ", "WRITE").contains(data.path("permission").asString())) {
            throw new AccessDeniedException("Invalid trip access response");
        }
        try {
            Instant checkedAt = Instant.parse(data.path("checkedAt").asString());
            Instant validUntil = Instant.parse(data.path("accessValidUntil").asString());
            if (checkedAt.isAfter(now.plusSeconds(5)) || !validUntil.isAfter(now)
                    || validUntil.isAfter(checkedAt.plusSeconds(60))) {
                throw new AccessDeniedException("Expired trip access response");
            }
            return validUntil;
        } catch (java.time.format.DateTimeParseException exception) {
            throw new AccessDeniedException("Invalid trip access time");
        }
    }

    public void execute(
            long tripId,
            String command,
            JsonNode body,
            WebSocketSessionCredentials credentials,
            String csrfToken
    ) {
        RestClient.RequestBodySpec request = restClient.post()
                .uri("/api/internal/websocket/trips/{tripId}/" + command, tripId);
        authenticate(request, credentials);
        JsonNode response = request.header("X-CSRF-TOKEN", csrfToken)
                .body(body).retrieve().body(JsonNode.class);
        JsonNode result = response == null ? null : response.get("data");
        if (result == null || !result.path("requestId").equals(body.path("requestId"))
                || !result.path("tripId").isIntegralNumber() || !result.path("tripId").canConvertToLong()
                || result.path("tripId").asLong() != tripId
                || !Set.of("SUCCEEDED", "REJECTED").contains(result.path("status").asString())
                || ("SUCCEEDED".equals(result.path("status").asString())
                && (!result.path("sequence").isIntegralNumber() || !result.path("sequence").canConvertToLong()
                || result.path("sequence").asLong() < 0))) {
            throw new RestClientException("Unknown command result");
        }
        // The durable result is delivered only through the Redis Outbox path.
    }

    private void authenticate(
            RestClient.RequestHeadersSpec<?> request,
            WebSocketSessionCredentials credentials
    ) {
        if (properties.serviceToken() == null || properties.serviceToken().isBlank()
                || credentials == null || credentials.accessToken() == null
                || credentials.accessToken().isBlank() || credentials.browserContext() == null
                || credentials.browserContext().isBlank()) {
            throw new AccessDeniedException("Missing backend credentials");
        }
        request.header("X-Internal-Service-Token", properties.serviceToken())
                .cookie("accessToken", credentials.accessToken())
                .cookie("CSRF_CONTEXT", credentials.browserContext());
    }
}
