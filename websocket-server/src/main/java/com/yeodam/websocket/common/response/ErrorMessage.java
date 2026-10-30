package com.yeodam.websocket.common.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ErrorMessage {
    CSRF_TOKEN_INVALID("CSRF 토큰이 유효하지 않습니다."),
    FORBIDDEN("접근 권한이 없습니다."),
    INTERNAL_SERVER_ERROR("서버 내부 오류가 발생했습니다."),
    INVALID_REQUEST("요청이 올바르지 않습니다."),
    PRESENCE_LIMIT_EXCEEDED("여행 참여 한도를 초과했습니다."),
    PRESENCE_NOT_JOINED("여행 참여 정보가 없습니다."),
    TRIP_NOT_FOUND("여행을 찾을 수 없습니다."),
    UNAUTHORIZED("인증이 필요합니다."),
    WS_SUBSCRIPTION_FORBIDDEN("해당 여행의 웹소켓 구독 권한이 없습니다.");

    private final String message;
}
