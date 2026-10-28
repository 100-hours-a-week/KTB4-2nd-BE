package com.yeodam.yeodambe.common.websocket.exception;

public enum WebSocketEventInternalErrorMessage {
    CHANNEL_NOT_CONFIGURED("웹소켓 이벤트 채널이 설정되지 않았습니다."),
    EVENT_MISSING("웹소켓 이벤트가 없습니다."),
    EVENT_ID_MISSING("웹소켓 이벤트 ID가 없습니다."),
    EVENT_TYPE_MISSING("웹소켓 이벤트 종류가 없습니다."),
    SCHEMA_VERSION_UNSUPPORTED("지원하지 않는 웹소켓 이벤트 버전입니다."),
    OCCURRED_AT_MISSING("웹소켓 이벤트 발생 시각이 없습니다."),
    TARGET_MISSING("웹소켓 이벤트 수신 대상이 없습니다."),
    TARGET_TYPE_MISSING("웹소켓 이벤트 수신 대상 종류가 없습니다."),
    TARGET_ID_INVALID("웹소켓 이벤트 수신 대상 ID가 올바르지 않습니다."),
    TARGET_TYPE_MISMATCH("웹소켓 이벤트 종류와 수신 대상이 일치하지 않습니다."),
    PAYLOAD_MISSING("웹소켓 이벤트 본문이 없습니다."),
    PAYLOAD_MESSAGE_MISSING("웹소켓 이벤트 본문의 메시지 코드가 없습니다."),
    EVENT_TOO_LARGE("웹소켓 이벤트 크기가 제한을 초과했습니다.");

    private final String message;

    WebSocketEventInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}