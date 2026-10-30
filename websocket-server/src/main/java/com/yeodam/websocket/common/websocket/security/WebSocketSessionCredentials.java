package com.yeodam.websocket.common.websocket.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public final class WebSocketSessionCredentials {

    public static final String ATTRIBUTE_NAME =
            WebSocketSessionCredentials.class.getName();

    private final String accessToken;
    private final String browserContext;
}
