package com.yeodam.websocket.common.websocket.session;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

public class WebSocketSessionTrackingDecorator
        extends WebSocketHandlerDecorator {

    private final WebSocketSessionManager sessionManager;

    public WebSocketSessionTrackingDecorator(
            WebSocketHandler delegate,
            WebSocketSessionManager sessionManager
    ) {
        super(delegate);
        this.sessionManager = sessionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session)
            throws Exception {
        if (!sessionManager.register(session)) {
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        try {
            super.afterConnectionEstablished(session);
        } catch (Exception exception) {
            sessionManager.unregister(session.getId());
            throw exception;
        }
    }

    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus closeStatus
    ) throws Exception {
        try {
            super.afterConnectionClosed(session, closeStatus);
        } finally {
            sessionManager.unregister(session.getId());
        }
    }
}
