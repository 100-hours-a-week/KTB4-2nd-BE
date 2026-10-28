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

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionManager {
    private static final Logger log =
            LoggerFactory.getLogger(WebSocketSessionManager.class);

    private final Map<String, WebSocketSession> sessions =
            new ConcurrentHashMap<>();

    private final Map<String, ScheduledFuture<?>> expirationTasks =
            new ConcurrentHashMap<>();

    private final TaskScheduler taskScheduler;

    public WebSocketSessionManager(
            @Qualifier("webSocketSessionTaskScheduler")
            TaskScheduler taskScheduler
    ) {
        this.taskScheduler = taskScheduler;
    }

    public synchronized void register(WebSocketSession session) {
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
    }

    public synchronized void unregister(String connectionId) {
        sessions.remove(connectionId);

        ScheduledFuture<?> expirationTask =
                expirationTasks.remove(connectionId);

        if (expirationTask != null) {
            expirationTask.cancel(false);
        }
    }

    public List<WebSocketSession> getSessions() {
        return List.copyOf(sessions.values());
    }

    private void closeExpiredSession(WebSocketSession session) {
        synchronized (this) {
            if (sessions.get(session.getId()) != session) {
                return;
            }

            expirationTasks.remove(session.getId());
        }

        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException exception) {
            log.warn(
                    "Failed to close expired WebSocket connection. connection_id={}",
                    session.getId(),
                    exception
            );
        }
    }
}