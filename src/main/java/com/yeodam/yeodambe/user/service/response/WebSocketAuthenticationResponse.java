package com.yeodam.yeodambe.user.service.response;

import java.time.Instant;

public record WebSocketAuthenticationResponse(
        Long userId,
        String sessionId,
        Instant accessTokenExpiresAt,
        String csrfToken
) {
}