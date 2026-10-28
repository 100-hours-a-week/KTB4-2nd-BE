package com.yeodam.websocket.common.websocket.session;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class WebSocketSessionManager {
    private static final Logger log =
            LoggerFactory.getLogger(WebSocketSessionManager.class);

    private final Map<String, WebSocketSession> sessions =
            new ConcurrentHashMap<>();

    private final Map<String, ScheduledFuture<?>> expirationTasks =
            new ConcurrentHashMap<>();

    private final TaskScheduler taskScheduler;
    private final RedisSubscriptionState subscriptionState;

    public WebSocketSessionManager(
            @Qualifier("webSocketSessionTaskScheduler")
            TaskScheduler taskScheduler,
            RedisSubscriptionState subscriptionState
    ) {
        this.taskScheduler = taskScheduler;
        this.subscriptionState = subscriptionState;
    }

    public synchronized boolean register(WebSocketSession session) {
        if (!subscriptionState.isReady()) {
            return false;
        }

        Authentication authentication =
                (Authentication) session.getPrincipal();

        WebSocketPrincipal principal =
                (WebSocketPrincipal) authentication.getPrincipal();

        sessions.put(session.getId(), session);

        try {
            ScheduledFuture<?> expirationTask = taskScheduler.schedule(
                    () -> closeExpiredSession(session),
                    principal.accessTokenExpiresAt()
            );

            expirationTasks.put(session.getId(), expirationTask);
        } catch (RuntimeException exception) {
            sessions.remove(session.getId());
            throw exception;
        }

        return true;
    }

    public synchronized void unregister(String connectionId) {
        WebSocketSession session = sessions.remove(connectionId);

        if (session != null) {
            session.getAttributes()
                    .remove(WebSocketSessionCredentials.ATTRIBUTE_NAME);
        }

        ScheduledFuture<?> expirationTask =
                expirationTasks.remove(connectionId);

        if (expirationTask != null) {
            expirationTask.cancel(false);
        }
    }

    public List<WebSocketSession> getSessions() {
        return List.copyOf(sessions.values());
    }

    public Map<String, List<WebSocketSession>> getSessionsByLoginSession() {
        return getSessions().stream()
                .collect(Collectors.groupingBy(session -> {
                    Authentication authentication =
                            (Authentication) session.getPrincipal();

                    WebSocketPrincipal principal =
                            (WebSocketPrincipal) authentication.getPrincipal();

                    return principal.sessionId();
                }));
    }

    public void closeConnection(String connectionId) {
        WebSocketSession session = sessions.get(connectionId);

        if (session == null) {
            return;
        }

        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException exception) {
            log.warn(
                    "Failed to close WebSocket connection. connection_id={}",
                    connectionId,
                    exception
            );
        }
    }

    public synchronized void closeConnectionsForRedisFailure() {
        if (!subscriptionState.markUnavailable()) {
            return;
        }

        List<WebSocketSession> affectedSessions = getSessions();

        taskScheduler.schedule(() -> {
            for (WebSocketSession session : affectedSessions) {
                try {
                    if (session.isOpen()) {
                        session.close(CloseStatus.SERVER_ERROR);
                    }
                } catch (IOException | RuntimeException exception) {
                    log.warn(
                            "Failed to close WebSocket after Redis failure. connection_id={}",
                            session.getId(),
                            exception
                    );
                }
            }
        }, Instant.now());
    }

    private void closeExpiredSession(WebSocketSession session) {
        synchronized (this) {
            if (sessions.get(session.getId()) != session) {
                return;
            }

            expirationTasks.remove(session.getId());
        }

        closeConnection(session.getId());
    }
}
