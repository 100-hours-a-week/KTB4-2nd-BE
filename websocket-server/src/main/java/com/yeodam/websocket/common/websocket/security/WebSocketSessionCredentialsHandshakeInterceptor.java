package com.yeodam.websocket.common.websocket.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

public class WebSocketSessionCredentialsHandshakeInterceptor
        implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        Object credentials = null;

        if (request instanceof ServletServerHttpRequest servletRequest) {
            credentials = servletRequest.getServletRequest()
                    .getAttribute(WebSocketSessionCredentials.ATTRIBUTE_NAME);
        }

        if (!(credentials instanceof WebSocketSessionCredentials)) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }

        attributes.put(
                WebSocketSessionCredentials.ATTRIBUTE_NAME,
                credentials
        );
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
    }
}