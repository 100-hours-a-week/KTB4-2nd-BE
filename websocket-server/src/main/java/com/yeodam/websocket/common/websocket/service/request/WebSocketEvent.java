package com.yeodam.websocket.common.websocket.service.request;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record WebSocketEvent(
        UUID eventId,
        WebSocketEventType type,
        int schemaVersion,
        Instant occurredAt,
        WebSocketEventTarget target,
        JsonNode payload
) {
}
