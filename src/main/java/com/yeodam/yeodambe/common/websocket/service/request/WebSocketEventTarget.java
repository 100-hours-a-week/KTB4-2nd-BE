package com.yeodam.yeodambe.common.websocket.service.request;

public record WebSocketEventTarget(
        WebSocketEventTargetType type,
        Long id
) {
}