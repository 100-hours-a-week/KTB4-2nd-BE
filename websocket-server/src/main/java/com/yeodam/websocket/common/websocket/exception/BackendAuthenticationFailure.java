package com.yeodam.websocket.common.websocket.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum BackendAuthenticationFailure {
    USER_UNAUTHORIZED("사용자 인증이 유효하지 않습니다."),
    SERVICE_FORBIDDEN("내부 서비스 인증 권한이 없습니다."),
    CSRF_INVALID("CSRF 토큰이 유효하지 않습니다."),
    BACKEND_UNAVAILABLE("백엔드 인증 서비스를 사용할 수 없습니다."),
    INVALID_RESPONSE("백엔드 인증 응답이 올바르지 않습니다."),
    SERVICE_TOKEN_MISSING("내부 서비스 인증 토큰이 없습니다.");

    private final String message;
}
