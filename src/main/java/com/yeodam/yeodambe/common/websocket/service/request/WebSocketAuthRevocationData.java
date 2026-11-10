package com.yeodam.yeodambe.common.websocket.service.request;

public record WebSocketAuthRevocationData(
        WebSocketAuthRevocationScope scope,
        String sid
) {
}
