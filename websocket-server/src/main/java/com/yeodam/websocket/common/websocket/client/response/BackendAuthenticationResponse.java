package com.yeodam.websocket.common.websocket.client.response;

import java.time.Instant;

public record BackendAuthenticationResponse(
        String message,
        AuthenticationData data
) {

    public record AuthenticationData(
            Long userId,
            String sessionId,
            Instant accessTokenExpiresAt,
            String csrfToken
    ) {
    }
}