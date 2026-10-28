package com.yeodam.yeodambe.common.websocket.service.request;

import com.yeodam.yeodambe.common.response.ApiResponse;

import java.time.Instant;
import java.util.UUID;

public record WebSocketEvent(
        UUID eventId,
        WebSocketEventType type,
        int schemaVersion,
        Instant occurredAt,
        WebSocketEventTarget target,
        ApiResponse<?> payload
) {
}