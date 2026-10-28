package com.yeodam.websocket.common.websocket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "websocket.events")
public record WebSocketEventProperties(
        String channel
) {
}