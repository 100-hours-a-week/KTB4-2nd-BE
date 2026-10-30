package com.yeodam.websocket.common.websocket.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum WebSocketConfigurationFailure {
    INVALID_MAX_CONNECTIONS("웹소켓 연결 상한은 양수여야 합니다.");

    private final String message;
}
