package com.yeodam.websocket.common.websocket.exception;

public enum BackendAuthenticationFailure {
    USER_UNAUTHORIZED,
    SERVICE_FORBIDDEN,
    CSRF_INVALID,
    BACKEND_UNAVAILABLE,
    INVALID_RESPONSE,
    SERVICE_TOKEN_MISSING
}