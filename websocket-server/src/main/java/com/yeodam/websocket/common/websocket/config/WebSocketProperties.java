package com.yeodam.websocket.common.websocket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import com.yeodam.websocket.common.websocket.exception.WebSocketConfigurationFailure;
import org.springframework.util.Assert;

import java.util.List;

@ConfigurationProperties(prefix = "websocket")
public record WebSocketProperties(
        List<String> allowedOrigins,
        int maxConnections
) {
    public WebSocketProperties {
        Assert.isTrue(
                maxConnections > 0,
                WebSocketConfigurationFailure.INVALID_MAX_CONNECTIONS.message()
        );
    }
}
