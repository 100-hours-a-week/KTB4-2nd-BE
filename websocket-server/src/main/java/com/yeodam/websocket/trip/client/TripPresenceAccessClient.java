package com.yeodam.websocket.trip.client;

import com.yeodam.websocket.common.response.ErrorMessage;
import com.yeodam.websocket.common.response.SuccessMessage;
import com.yeodam.websocket.common.websocket.config.BackendAuthenticationProperties;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.trip.client.response.TripPresenceAccessResponse;
import com.yeodam.websocket.trip.client.response.TripPresenceAccessResponse.AccessData;
import com.yeodam.websocket.trip.exception.TripPresenceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Set;

@Component
public class TripPresenceAccessClient {
    private final RestClient client;
    private final BackendAuthenticationProperties properties;

    public TripPresenceAccessClient(
            @Qualifier("backendAuthenticationRestClient") RestClient client,
            BackendAuthenticationProperties properties
    ) {
        this.client = client;
        this.properties = properties;
    }

    public AccessData findPresenceAccess(
            Long tripId, Long expectedUserId, WebSocketSessionCredentials credentials
    ) {
        if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
            throw new TripPresenceException(ErrorMessage.INTERNAL_SERVER_ERROR);
        }
        try {
            AccessData access = client.get()
                    .uri("/api/internal/websocket/trips/{tripId}/presence-access", tripId)
                    .header("X-Internal-Service-Token", properties.serviceToken())
                    .cookie("accessToken", credentials.accessToken())
                    .cookie("CSRF_CONTEXT", credentials.browserContext())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401) {
                            throw new TripPresenceException(ErrorMessage.UNAUTHORIZED);
                        }
                        if (status == 404) {
                            throw new TripPresenceException(ErrorMessage.TRIP_NOT_FOUND);
                        }
                        if (status != 200) {
                            throw new TripPresenceException(ErrorMessage.INTERNAL_SERVER_ERROR);
                        }
                        TripPresenceAccessResponse body = response.bodyTo(TripPresenceAccessResponse.class);
                        if (body == null || !SuccessMessage.TRIP_PRESENCE_ACCESS_FOUND.name().equals(body.message())) {
                            throw new TripPresenceException(ErrorMessage.INTERNAL_SERVER_ERROR);
                        }
                        return body.data();
                    });
            validateResponse(access, tripId, expectedUserId);
            return access;
        } catch (RestClientException exception) {
            throw new TripPresenceException(ErrorMessage.INTERNAL_SERVER_ERROR);
        }
    }

    private void validateResponse(AccessData access, Long tripId, Long expectedUserId) {
        if (access == null) {
            throw new TripPresenceException(ErrorMessage.INTERNAL_SERVER_ERROR);
        }
        validateIdentity(access, tripId, expectedUserId);
        validateAccessWindow(access);
    }

    private void validateIdentity(AccessData access, Long tripId, Long expectedUserId) {
        if (!tripId.equals(access.tripId()) || !expectedUserId.equals(access.userId())
                || access.nickname() == null || access.nickname().isBlank()
                || access.nickname().length() > 10 || access.permission() == null
                || !Set.of("OWNER", "READ", "WRITE").contains(access.permission())
                || (access.profileImageUrl() != null && access.profileImageUrl().length() > 2048)) {
            throw new TripPresenceException(ErrorMessage.INTERNAL_SERVER_ERROR);
        }
    }

    private void validateAccessWindow(AccessData access) {
        Instant now = Instant.now();
        if (access.checkedAt() == null || access.accessValidUntil() == null
                || !access.accessValidUntil().isAfter(now)
                || access.checkedAt().isAfter(now.plusSeconds(2))
                || !access.accessValidUntil().isAfter(access.checkedAt())
                || access.accessValidUntil().isAfter(access.checkedAt().plusSeconds(30))) {
            throw new TripPresenceException(ErrorMessage.INTERNAL_SERVER_ERROR);
        }
        if (!"OWNER".equals(access.permission()) && (access.shareExpiresAt() == null
                || access.accessValidUntil().isAfter(access.shareExpiresAt()))) {
            throw new TripPresenceException(ErrorMessage.INTERNAL_SERVER_ERROR);
        }
    }
}
