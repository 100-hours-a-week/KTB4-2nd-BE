package com.yeodam.websocket.common.websocket.service.request;

public record WebSocketEventTarget(
        WebSocketEventTargetType type,
        Long id
) {
}
