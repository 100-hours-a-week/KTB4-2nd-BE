package com.yeodam.websocket.common.websocket.exception;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class WebSocketEventValidationException extends IllegalArgumentException {
    private final WebSocketEventFailure failure;

    public WebSocketEventValidationException(WebSocketEventFailure failure) {
        super(failure.message());
        this.failure = failure;
    }
}
