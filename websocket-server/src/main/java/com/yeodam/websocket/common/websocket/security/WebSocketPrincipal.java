package com.yeodam.websocket.common.websocket.security;

import java.security.Principal;
import java.time.Instant;

public record WebSocketPrincipal(
        Long userId,
        String sessionId,
        Instant accessTokenExpiresAt
) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}