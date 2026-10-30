package com.yeodam.websocket.common.websocket.logging;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketStatusLogger {

    private final WebSocketSessionManager sessionManager;
    private final SimpUserRegistry userRegistry;
    private final RedisSubscriptionState subscriptionState;

    @Scheduled(scheduler = "webSocketSessionTaskScheduler", initialDelay = 60_000, fixedDelay = 60_000)
    public void logStatus() {
        int connections = sessionManager.getSessions().size();
        int subscriptions = userRegistry.getUsers().stream()
                .flatMap(user -> user.getSessions().stream())
                .mapToInt(session -> session.getSubscriptions().size())
                .sum();

        log.atInfo().addKeyValue("event", "websocket_status")
                .addKeyValue("connections", connections)
                .addKeyValue("subscriptions", subscriptions)
                .addKeyValue("redis_subscription_ready", subscriptionState.isReady())
                .log("WebSocket server status.");
    }
}
