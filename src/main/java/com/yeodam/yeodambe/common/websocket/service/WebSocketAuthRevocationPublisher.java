package com.yeodam.yeodambe.common.websocket.service;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketAuthRevocationData;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketAuthRevocationScope;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthRevocationPublisher {

    private final WebSocketEventPublisher eventPublisher;

    public void revokeSession(Long userId, String sid) {
        publish(userId, new WebSocketAuthRevocationData(WebSocketAuthRevocationScope.SESSION, sid));
    }

    public void revokeUser(Long userId) {
        publish(userId, new WebSocketAuthRevocationData(WebSocketAuthRevocationScope.USER, null));
    }

    private void publish(Long userId, WebSocketAuthRevocationData data) {
        WebSocketEvent event = new WebSocketEvent(
                UUID.randomUUID(),
                WebSocketEventType.AUTH_REVOKED,
                1,
                Instant.now(),
                new WebSocketEventTarget(WebSocketEventTargetType.USER, userId),
                new ApiResponse<>(WebSocketEventType.AUTH_REVOKED.name(), data)
        );

        try {
            eventPublisher.publish(event);
        } catch (RuntimeException exception) {
            log.atWarn().addKeyValue("event", "websocket_auth_revocation")
                    .addKeyValue("result", "failure")
                    .addKeyValue("failure", exception.getClass().getName())
                    .log("WebSocket auth revocation publication failed.");
        }
    }
}
