package com.yeodam.yeodambe.user.security.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.websocket")
public record WebSocketAuthenticationProperties(
        String internalServiceToken
) {
}