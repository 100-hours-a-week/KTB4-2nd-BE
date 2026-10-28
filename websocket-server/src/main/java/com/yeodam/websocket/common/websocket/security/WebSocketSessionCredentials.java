package com.yeodam.websocket.common.websocket.security;

public final class WebSocketSessionCredentials {

    public static final String ATTRIBUTE_NAME =
            WebSocketSessionCredentials.class.getName();

    private final String accessToken;
    private final String browserContext;

    public WebSocketSessionCredentials(
            String accessToken,
            String browserContext
    ) {
        this.accessToken = accessToken;
        this.browserContext = browserContext;
    }

    public String accessToken() {
        return accessToken;
    }

    public String browserContext() {
        return browserContext;
    }
}