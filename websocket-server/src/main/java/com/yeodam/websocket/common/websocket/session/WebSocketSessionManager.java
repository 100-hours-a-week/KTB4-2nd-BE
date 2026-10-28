package com.yeodam.websocket.common.websocket.session;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionManager {

    private final Map<String, WebSocketSession> sessions =
            new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    public void unregister(String connectionId) {
        sessions.remove(connectionId);
    }

    public List<WebSocketSession> getSessions() {
        return List.copyOf(sessions.values());
    }
}